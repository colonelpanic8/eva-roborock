package com.colonelpanic.roborock.eva

import com.colonelpanic.roborock.api.Account
import com.colonelpanic.roborock.api.Robot
import com.colonelpanic.roborock.api.RpcSession
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class CapabilitiesTest {
    private val account =
        Account(
            email = "user@example.com",
            token = "token",
            rriot = Account.Rriot("u", "s", "h", "k", "https://api", "ssl://mqtt:8883"),
            device = Account.Device("duid", "Robot", "0123456789abcdef", "roborock.vacuum.a168"),
        )
    private val sent = mutableListOf<Pair<String, String>>()
    private var failOn: String? = null
    private var silentOn: String? = null
    private var journalText: String? = null

    private val session =
        object : RpcSession {
            override fun call(
                method: String,
                params: Any?,
                deadline: Long,
            ): Any {
                sent += method to EvaProtocol.canonical(params)
                if (method == failOn) throw Robot.RpcError(method, "{\"code\":-10007}")
                if (method == silentOn) throw Robot.NoReply(method)
                return JSONArray().put("ok")
            }

            override fun close() = Unit
        }

    private val capabilities =
        Capabilities(
            configuration = { Capabilities.Configuration(account, ROOMS, PRESETS) },
            journal =
                Journal(
                    object : Journal.Store {
                        override fun read() = journalText

                        override fun write(text: String) {
                            journalText = text
                        }
                    },
                    now = { 0L },
                ),
            connect = { _, _ -> session },
            elapsedNow = { 0L },
        )

    private val revision = JSONObject(capabilities.describe()).getJSONObject("descriptor").getString("descriptorRevision")

    private fun execute(
        tool: String,
        args: String,
        id: String = "eva-1",
        expectedRevision: String = revision,
    ) = JSONObject(capabilities.execute(id, expectedRevision, tool, args, deadline = 30_000))

    @Test
    fun cleansResolvedRoomsWithTheRequestedModes() {
        val reply = execute("clean_rooms", """{"rooms": ["dining", "living room"], "mode": "vacuum", "suction": "max"}""")
        assertEquals("completed", reply.getString("status"))
        assertEquals(
            listOf(
                "set_water_box_custom_mode" to "[200]",
                "set_custom_mode" to "[104]",
                "app_segment_clean" to """[{"repeat":1,"segments":[4,7]}]""",
            ),
            sent,
        )
    }

    @Test
    fun mopOnlyPresetTurnsSuctionOff() {
        execute("run_preset", """{"preset": "Kitchen Mop"}""")
        assertEquals("set_clean_motor_mode" to """[{"fan_power":105,"water_box_mode":203}]""", sent.first())
    }

    @Test
    fun replayedInvocationIsNeverResent() {
        val first = execute("pause", "{}")
        val second = execute("pause", "{}")
        assertEquals(1, sent.size)
        assertEquals(first.toString(), second.toString())
    }

    @Test
    fun reusedInvocationIdWithDifferentArgumentsIsAConflict() {
        execute("clean_rooms", """{"rooms": ["kitchen"]}""")
        val reply = execute("clean_rooms", """{"rooms": ["living room"]}""")
        assertEquals("not_executed", reply.getString("status"))
        assertEquals("request_id_conflict", reply.getJSONObject("structuredContent").getString("state"))
        assertEquals(2, sent.size)
    }

    @Test
    fun unconfirmedStartIsUnknownButUnconfirmedSetupIsAFailure() {
        silentOn = "app_segment_clean"
        assertEquals("unknown", execute("clean_rooms", """{"rooms": ["kitchen"]}""", id = "eva-a").getString("status"))
        silentOn = "set_clean_motor_mode"
        assertEquals("failed", execute("clean_rooms", """{"rooms": ["kitchen"]}""", id = "eva-b").getString("status"))
    }

    @Test
    fun refusedFirstCommandMeansNothingRan() {
        failOn = "app_pause"
        val reply = execute("pause", "{}")
        assertEquals("not_executed", reply.getString("status"))
    }

    @Test
    fun unknownRoomOrStaleRevisionSendsNothing() {
        assertEquals("invalid_arguments", execute("clean_rooms", """{"rooms": ["garage"]}""", id = "eva-a").getString("reasonCode"))
        assertEquals("stale_descriptor", execute("pause", "{}", id = "eva-b", expectedRevision = "old").getString("reasonCode"))
        assertEquals(emptyList<Pair<String, String>>(), sent)
    }

    companion object {
        private const val ROOMS = "4 = Kitchen, dining\n7 = Living Room"
        private const val PRESETS = """{"kitchen mop": {"rooms": ["kitchen"], "mode": "mop"}}"""
    }
}
