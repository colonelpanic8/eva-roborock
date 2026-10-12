package com.colonelpanic.roborock.api

import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Read-only calls against the real robot; runs only with ROBOROCK_LIVE=1 and a python-roborock session. */
class LiveRobotTest {
    @Test
    fun readsStatusAndRoomMapping() {
        val cache = File(System.getProperty("user.home"), ".roborock")
        assumeTrue(System.getenv("ROBOROCK_LIVE") == "1" && cache.exists())
        val account = Account.fromPythonRoborockCache(JSONObject(cache.readText()))
        Robot(account).open(System.currentTimeMillis() + 15_000).use { session ->
            val status = session.call("get_status", deadline = System.currentTimeMillis() + 15_000)
            println("status: ${Cleaning.describeStatus((status as org.json.JSONArray).getJSONObject(0)).first}")
            val maps = session.call("get_multi_maps_list", deadline = System.currentTimeMillis() + 15_000)
            println("rooms: ${Cleaning.roomsFromMaps(maps, emptyList())}")
        }
    }
}
