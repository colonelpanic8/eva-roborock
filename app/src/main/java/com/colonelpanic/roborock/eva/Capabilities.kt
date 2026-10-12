package com.colonelpanic.roborock.eva

import com.colonelpanic.roborock.api.Account
import com.colonelpanic.roborock.api.CleanRequest
import com.colonelpanic.roborock.api.Cleaning
import com.colonelpanic.roborock.api.MiniMqtt
import com.colonelpanic.roborock.api.Preset
import com.colonelpanic.roborock.api.Presets
import com.colonelpanic.roborock.api.Robot
import com.colonelpanic.roborock.api.Room
import com.colonelpanic.roborock.api.RpcSession
import com.colonelpanic.roborock.eva.EvaProtocol.Reply
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

/** EVA's view of this app: the tool catalog and how each tool runs. */
class Capabilities(
    private val configuration: () -> Configuration,
    private val journal: Journal,
    private val connect: (Account, Long) -> RpcSession,
    private val elapsedNow: () -> Long,
    private val wallNow: () -> Long = System::currentTimeMillis,
) {
    class Configuration(
        val account: Account?,
        val roomsText: String,
        val presetsText: String,
    )

    fun describe(): String {
        val config = configuration()
        return EvaProtocol.describeEnvelope(
            JSONObject()
                .put("protocolVersion", EvaProtocol.PROTOCOL_VERSION)
                .put("descriptorRevision", revision(config))
                .put("authorizationScopeRevision", scope(config))
                .put("title", "Roborock")
                .put("capabilities", JSONArray(TOOLS.map { it.json() })),
        )
    }

    fun execute(
        invocationId: String,
        expectedRevision: String,
        capability: String,
        argumentsJson: String,
        deadline: Long,
    ): String {
        val tool =
            TOOLS.firstOrNull { it.name == capability }
                ?: return reject(
                    capability,
                    invocationId,
                    EvaProtocol.REASON_INVALID_ARGUMENTS,
                    "Unknown action '$capability'.",
                    "invalid_request",
                )
        val config = configuration()
        if (expectedRevision != revision(config)) {
            return reject(
                tool,
                invocationId,
                EvaProtocol.REASON_STALE_DESCRIPTOR,
                "The Roborock account or actions changed since EVA approved them. Nothing was sent.",
                "rejected",
            )
        }
        val arguments =
            try {
                JSONObject(argumentsJson)
            } catch (e: org.json.JSONException) {
                return reject(
                    tool,
                    invocationId,
                    EvaProtocol.REASON_INVALID_ARGUMENTS,
                    "Arguments were not a JSON object. Nothing was sent.",
                    "invalid_request",
                )
            }
        val budget = deadline - elapsedNow() - EvaProtocol.REPLY_MARGIN_MILLIS
        val wallDeadline = wallNow() + budget
        if (budget <=
            0
        ) {
            return reject(
                tool,
                invocationId,
                EvaProtocol.REASON_DEADLINE_EXCEEDED,
                "EVA's deadline passed before the request started. Nothing was sent.",
                "expired",
            )
        }
        if (!tool.write) {
            return EvaProtocol.executeEnvelope(
                try {
                    read(tool.name, arguments, config, wallDeadline)
                } catch (e: Exception) {
                    EvaProtocol.failed(explain(e))
                },
            )
        }

        val digest = EvaProtocol.sha256Hex(tool.name + "\n" + EvaProtocol.canonical(arguments))
        journal.get(invocationId)?.let { existing ->
            if (existing.digest != digest || existing.capability != tool.name) {
                return reject(
                    tool,
                    invocationId,
                    EvaProtocol.REASON_INVALID_ARGUMENTS,
                    "This invocation ID was already used for a different request. Nothing was sent.",
                    "request_id_conflict",
                )
            }
            return existing.envelope ?: EvaProtocol.executeEnvelope(
                EvaProtocol.unknown(
                    "This request was already submitted and its outcome was not recorded. Check the robot.",
                    structured = receipt(invocationId, "uncertain"),
                ),
            )
        }

        val account =
            config.account
                ?: return reject(
                    tool,
                    invocationId,
                    EvaProtocol.REASON_NOT_CONFIGURED,
                    "Roborock for EVA is not signed in. Open the Roborock for EVA app and sign in. Nothing was sent.",
                    "needs_configuration",
                )
        val plan =
            try {
                plan(tool.name, arguments, config)
            } catch (e: IllegalArgumentException) {
                return reject(
                    tool,
                    invocationId,
                    EvaProtocol.REASON_INVALID_ARGUMENTS,
                    e.message.orEmpty() + " Nothing was sent.",
                    "invalid_request",
                )
            }
        if (!journal.begin(invocationId, tool.name, digest)) {
            return EvaProtocol.executeEnvelope(
                EvaProtocol.unknown(
                    "A concurrent request used this invocation ID. Check the robot.",
                    structured = receipt(invocationId, "uncertain"),
                ),
            )
        }
        val (state, reply) = run(account, plan, invocationId, wallDeadline)
        val envelope = EvaProtocol.executeEnvelope(reply)
        journal.finish(invocationId, state, envelope)
        return envelope
    }

    fun rejection(
        capability: String,
        invocationId: String,
        reason: String,
        text: String,
        state: String,
    ): String = reject(capability, invocationId, reason, text, state)

    private class Plan(
        val commands: List<Pair<String, Any>>,
        val summary: String,
        /** Chooses the commands from the robot's status; throws [IllegalStateException] to refuse. */
        val fromStatus: ((JSONObject) -> Pair<List<Pair<String, Any>>, String>)? = null,
    )

    private fun plan(
        tool: String,
        arguments: JSONObject,
        config: Configuration,
    ): Plan =
        when (tool) {
            "clean_rooms" -> {
                val rooms = arguments.optJSONArray("rooms") ?: throw IllegalArgumentException("Give at least one room.")
                val names = (0 until rooms.length()).map { rooms.get(it).toString() }
                if (names.isEmpty()) throw IllegalArgumentException("Give at least one room, or use clean_home.")
                cleanPlan(
                    names,
                    arguments.optStringOrNull("mode"),
                    arguments.optStringOrNull("suction"),
                    arguments.optStringOrNull("water"),
                    arguments.optInt("repeat", 1),
                    config,
                )
            }

            "clean_home" -> {
                cleanPlan(
                    emptyList(),
                    arguments.optStringOrNull("mode"),
                    arguments.optStringOrNull("suction"),
                    arguments.optStringOrNull("water"),
                    1,
                    config,
                )
            }

            "run_preset" -> {
                val name = arguments.optString("preset")
                val presets = presets(config)
                val preset =
                    Presets.find(presets, name)
                        ?: throw IllegalArgumentException(
                            "No preset named '$name'. Presets: ${presets.joinToString { it.name }.ifEmpty { "none configured" }}.",
                        )
                cleanPlan(preset.rooms, preset.mode, preset.suction, preset.water, preset.repeat, config, "the '${preset.name}' preset: ")
            }

            "pause" -> {
                Plan(listOf("app_pause" to JSONArray()), "Paused the robot.")
            }

            "resume" -> {
                Plan(emptyList(), "", fromStatus = Cleaning::resumeCommand)
            }

            "return_to_dock" -> {
                Plan(listOf("app_charge" to JSONArray()), "Sent the robot back to its dock.")
            }

            "locate" -> {
                Plan(listOf("find_me" to JSONArray()), "The robot is announcing its location.")
            }

            else -> {
                throw IllegalArgumentException("Unknown action '$tool'.")
            }
        }

    private fun cleanPlan(
        requested: List<String>,
        modeName: String?,
        suctionName: String?,
        waterName: String?,
        repeat: Int,
        config: Configuration,
        prefix: String = "",
    ): Plan {
        val mode = Cleaning.mode(modeName) ?: throw IllegalArgumentException("Unknown mode '$modeName'.")
        val suction = Cleaning.suction(suctionName) ?: throw IllegalArgumentException("Unknown suction '$suctionName'.")
        val water = Cleaning.water(waterName) ?: throw IllegalArgumentException("Unknown water level '$waterName'.")
        if (repeat !in 1..3) throw IllegalArgumentException("Repeat must be 1 to 3.")
        val rooms = rooms(config)
        val (resolved, unknown) = Cleaning.resolveRooms(requested, rooms)
        if (unknown.isNotEmpty()) {
            throw IllegalArgumentException(
                "Unknown room ${unknown.joinToString { "'$it'" }}. Rooms: ${rooms.joinToString { it.name }.ifEmpty { "none named yet" }}.",
            )
        }
        val request = CleanRequest(resolved.map { it.segment }, mode, suction, water, repeat)
        val where = if (resolved.isEmpty()) "the whole home" else resolved.joinToString { it.name }
        val how =
            when (mode) {
                com.colonelpanic.roborock.api.CleanMode.VACUUM -> "vacuum only, ${suction.wire} suction"
                com.colonelpanic.roborock.api.CleanMode.VACUUM_AND_MOP -> "vacuum and mop, ${suction.wire} suction, ${water.wire} water"
                com.colonelpanic.roborock.api.CleanMode.MOP -> "mop only, ${water.wire} water"
            }
        val times = if (repeat > 1) ", $repeat passes" else ""
        return Plan(request.commands(), "${prefix}Started cleaning $where ($how$times).")
    }

    /** Runs [plan]; returns the receipt state and reply. Only the last command has a user-visible effect. */
    private fun run(
        account: Account,
        plan: Plan,
        invocationId: String,
        deadline: Long,
    ): Pair<String, Reply> {
        val session =
            try {
                connect(account, deadline)
            } catch (e: MiniMqtt.AuthenticationException) {
                return outcome(
                    invocationId,
                    "needs_authorization",
                    EvaProtocol.STATUS_NOT_EXECUTED,
                    EvaProtocol.REASON_NOT_CONFIGURED,
                    "${e.message} Open Roborock for EVA and sign in again. Nothing was sent.",
                )
            } catch (e: Exception) {
                val reason = if (wallNow() >= deadline) EvaProtocol.REASON_DEADLINE_EXCEEDED else null
                return outcome(
                    invocationId,
                    "not_sent",
                    EvaProtocol.STATUS_NOT_EXECUTED,
                    reason,
                    "Could not reach Roborock's servers: ${explain(e)} Nothing was sent.",
                )
            }
        session.use {
            var commands = plan.commands
            var summary = plan.summary
            plan.fromStatus?.let { choose ->
                val status =
                    try {
                        statusOf(it.call("get_status", null, deadline))
                    } catch (e: Exception) {
                        return outcome(
                            invocationId,
                            "not_sent",
                            EvaProtocol.STATUS_NOT_EXECUTED,
                            null,
                            "Could not read the robot's status: ${explain(e)} Nothing was sent.",
                        )
                    }
                try {
                    choose(status).let { (chosen, text) ->
                        commands = chosen
                        summary = text
                    }
                } catch (e: IllegalStateException) {
                    return outcome(invocationId, "rejected", EvaProtocol.STATUS_NOT_EXECUTED, null, "${e.message} Nothing was sent.")
                }
            }
            commands.forEachIndexed { index, (method, params) ->
                val last = index == commands.lastIndex
                try {
                    it.call(method, params, deadline)
                } catch (e: Robot.RpcError) {
                    return when {
                        index == 0 -> {
                            outcome(
                                invocationId,
                                "rejected",
                                EvaProtocol.STATUS_NOT_EXECUTED,
                                null,
                                "${e.message}. Nothing changed.",
                            )
                        }

                        last -> {
                            outcome(
                                invocationId,
                                "failed",
                                EvaProtocol.STATUS_FAILED,
                                null,
                                "${e.message}. The robot did not start, though its suction/water settings were updated.",
                            )
                        }

                        else -> {
                            outcome(
                                invocationId,
                                "failed",
                                EvaProtocol.STATUS_FAILED,
                                null,
                                "${e.message}. The robot did not start; some settings may have changed.",
                            )
                        }
                    }
                } catch (e: Robot.NoReply) {
                    return if (last) {
                        outcome(
                            invocationId,
                            "uncertain",
                            EvaProtocol.STATUS_UNKNOWN,
                            null,
                            "Sent $method but the robot did not confirm in time; it may have started. Check its status before retrying.",
                        )
                    } else {
                        outcome(
                            invocationId,
                            "failed",
                            EvaProtocol.STATUS_FAILED,
                            null,
                            "The robot did not confirm $method in time, so cleaning was not started. Its settings may have changed.",
                        )
                    }
                } catch (e: IOException) {
                    return if (last) {
                        outcome(
                            invocationId,
                            "uncertain",
                            EvaProtocol.STATUS_UNKNOWN,
                            null,
                            "The connection dropped while sending $method; the robot may have started. Check its status before retrying.",
                        )
                    } else {
                        outcome(
                            invocationId,
                            "failed",
                            EvaProtocol.STATUS_FAILED,
                            null,
                            "The connection dropped before cleaning was started (${explain(e)}). Settings may have changed.",
                        )
                    }
                }
            }
            return outcome(invocationId, "completed", EvaProtocol.STATUS_COMPLETED, null, "$summary The robot confirmed the command.")
        }
    }

    private fun outcome(
        invocationId: String,
        state: String,
        status: String,
        reason: String?,
        text: String,
    ): Pair<String, Reply> = state to Reply(status, reason, text, receipt(invocationId, state))

    private fun read(
        tool: String,
        arguments: JSONObject,
        config: Configuration,
        deadline: Long,
    ): Reply =
        when (tool) {
            "list_rooms" -> {
                val rooms = rooms(config)
                val presets = presets(config)
                val text =
                    buildString {
                        append("Rooms: ")
                            .append(
                                rooms
                                    .joinToString { room ->
                                        room.name + room.aliases.joinToString("") { " / $it" }
                                    }.ifEmpty { "none named yet" },
                            ).append('.')
                        append(" Presets: ").append(presets.joinToString { it.name }.ifEmpty { "none" }).append('.')
                    }
                EvaProtocol.completed(
                    text,
                    JSONObject()
                        .put(
                            "rooms",
                            JSONArray(
                                rooms.map {
                                    JSONObject()
                                        .put(
                                            "name",
                                            it.name,
                                        ).put("aliases", JSONArray(it.aliases))
                                        .put("segment", it.segment)
                                },
                            ),
                        ).put("presets", JSONArray(presets.map { presetJson(it) })),
                )
            }

            "status" -> {
                val account =
                    config.account
                        ?: return EvaProtocol.notExecuted(
                            EvaProtocol.REASON_NOT_CONFIGURED,
                            "Roborock for EVA is not signed in. Open the app and sign in.",
                        )
                val result = connect(account, deadline).use { it.call("get_status", null, deadline) }
                val status =
                    (result as? JSONArray)?.optJSONObject(0) ?: result as? JSONObject ?: throw IOException("Unexpected status reply.")
                val (text, structured) = Cleaning.describeStatus(status)
                EvaProtocol.completed(text, structured)
            }

            "invocation_status" -> {
                val id = arguments.optString("invocationId")
                val entry = journal.get(id) ?: return EvaProtocol.completed("No record of that request.", receipt(id, "not_found"))
                val state = if (entry.envelope == null) "uncertain" else entry.state
                EvaProtocol.completed("That request is $state.", receipt(id, state).put("capability", entry.capability))
            }

            else -> {
                EvaProtocol.notExecuted(EvaProtocol.REASON_INVALID_ARGUMENTS, "Unknown action '$tool'.")
            }
        }

    private fun statusOf(result: Any): JSONObject =
        (result as? JSONArray)?.optJSONObject(0) ?: result as? JSONObject ?: throw IOException("Unexpected status reply.")

    private fun rooms(config: Configuration): List<Room> =
        try {
            Cleaning.parseRooms(config.roomsText)
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException("The room list in Roborock for EVA is invalid: ${e.message}")
        }

    private fun presets(config: Configuration): List<Preset> =
        try {
            Presets.parse(config.presetsText)
        } catch (e: org.json.JSONException) {
            throw IllegalArgumentException("The presets in Roborock for EVA are not valid JSON: ${e.message}")
        }

    private fun presetJson(preset: Preset) =
        JSONObject()
            .put("name", preset.name)
            .put("rooms", JSONArray(preset.rooms))
            .put("mode", preset.mode ?: "vacuum_and_mop")
            .put("suction", preset.suction ?: "balanced")
            .put("water", preset.water ?: "high")
            .put("repeat", preset.repeat)

    private fun reject(
        tool: Tool,
        invocationId: String,
        reason: String,
        text: String,
        state: String,
    ): String = reject(tool.name, invocationId, reason, text, state)

    private fun reject(
        capability: String,
        invocationId: String,
        reason: String,
        text: String,
        state: String,
    ): String {
        val write = TOOLS.firstOrNull { it.name == capability }?.write ?: true
        return EvaProtocol.executeEnvelope(EvaProtocol.notExecuted(reason, text, if (write) receipt(invocationId, state) else null))
    }

    private fun receipt(
        invocationId: String,
        state: String,
    ) = JSONObject().put("invocationId", invocationId).put("state", state)

    private fun explain(e: Exception): String = e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName

    private fun scope(config: Configuration) = config.account?.let { "account-" + it.scopeId } ?: "signed-out"

    private fun revision(config: Configuration) = "$CATALOG_REVISION.${scope(config)}"

    private fun JSONObject.optStringOrNull(key: String): String? = if (has(key) && !isNull(key)) optString(key).ifEmpty { null } else null

    private class Tool(
        val name: String,
        val title: String,
        val description: String,
        val write: Boolean,
        val properties: JSONObject = JSONObject(),
        val required: List<String> = emptyList(),
    ) {
        fun json(): JSONObject =
            JSONObject()
                .put(
                    "tool",
                    JSONObject()
                        .put("name", name)
                        .put("title", title)
                        .put("description", description)
                        .put(
                            "inputSchema",
                            JSONObject()
                                .put(
                                    "type",
                                    "object",
                                ).put("properties", properties)
                                .put("required", JSONArray(required))
                                .put("additionalProperties", false),
                        ).put("annotations", JSONObject().put("readOnlyHint", !write).put("openWorldHint", true)),
                ).put("effects", if (write) "write" else "read")
                .put(
                    "execution",
                    JSONObject().put("mode", "synchronous").put("requiresForeground", false).put("maxWaitMillis", MAX_WAIT_MILLIS),
                ).put("result", JSONObject().put("maxBytes", EvaProtocol.MAX_EXECUTE_BYTES))
    }

    companion object {
        private const val CATALOG_REVISION = "catalog-2"
        private const val MAX_WAIT_MILLIS = 30_000

        private fun enumProperty(
            description: String,
            values: List<String>,
        ) = JSONObject().put("type", "string").put("enum", JSONArray(values)).put("description", description)

        private val MODE =
            enumProperty(
                "vacuum, vacuum_and_mop, or mop (mop only, no suction). Default vacuum_and_mop.",
                listOf("vacuum", "vacuum_and_mop", "mop"),
            )
        private val SUCTION = enumProperty("Suction power. Default balanced.", listOf("quiet", "balanced", "turbo", "max", "max_plus"))
        private val WATER = enumProperty("Mop water flow when mopping. Default high.", listOf("low", "medium", "high"))

        private val TOOLS =
            listOf(
                Tool(
                    "status",
                    "Robot vacuum status",
                    "Read the Roborock robot vacuum's current state, battery, suction, water level and cleaning progress.",
                    write = false,
                ),
                Tool(
                    "list_rooms",
                    "List vacuum rooms and presets",
                    "List the room names (with aliases) the Roborock vacuum can clean and the user's saved cleaning presets. No network access.",
                    write = false,
                ),
                Tool(
                    "clean_rooms",
                    "Clean rooms",
                    "Start the Roborock vacuum cleaning specific rooms. Room names are matched loosely against the configured names and aliases; call list_rooms if unsure.",
                    write = true,
                    properties =
                        JSONObject()
                            .put(
                                "rooms",
                                JSONObject()
                                    .put(
                                        "type",
                                        "array",
                                    ).put(
                                        "minItems",
                                        1,
                                    ).put("items", JSONObject().put("type", "string"))
                                    .put("description", "Room names or aliases."),
                            ).put("mode", MODE)
                            .put("suction", SUCTION)
                            .put("water", WATER)
                            .put(
                                "repeat",
                                JSONObject()
                                    .put(
                                        "type",
                                        "integer",
                                    ).put("minimum", 1)
                                    .put("maximum", 3)
                                    .put("description", "Passes over each room. Default 1."),
                            ),
                    required = listOf("rooms"),
                ),
                Tool(
                    "clean_home",
                    "Clean the whole home",
                    "Start the Roborock vacuum cleaning the entire home.",
                    write = true,
                    properties = JSONObject().put("mode", MODE).put("suction", SUCTION).put("water", WATER),
                ),
                Tool(
                    "run_preset",
                    "Run a cleaning preset",
                    "Start one of the user's saved cleaning presets by name (see list_rooms for the names).",
                    write = true,
                    properties = JSONObject().put("preset", JSONObject().put("type", "string").put("description", "Preset name.")),
                    required = listOf("preset"),
                ),
                Tool("pause", "Pause the vacuum", "Pause the Roborock vacuum's current cleaning.", write = true),
                Tool(
                    "resume",
                    "Resume the vacuum",
                    "Resume the Roborock vacuum's paused or interrupted cleaning where it left off.",
                    write = true,
                ),
                Tool(
                    "return_to_dock",
                    "Send the vacuum home",
                    "Stop cleaning and send the Roborock vacuum back to its dock.",
                    write = true,
                ),
                Tool("locate", "Find the vacuum", "Make the Roborock vacuum announce where it is.", write = true),
                Tool(
                    "invocation_status",
                    "Check a vacuum request",
                    "Look up the recorded outcome of an earlier Roborock action by its invocationId.",
                    write = false,
                    properties =
                        JSONObject().put(
                            "invocationId",
                            JSONObject().put("type", "string").put("minLength", 1).put("maxLength", 256),
                        ),
                    required = listOf("invocationId"),
                ),
            )
    }
}
