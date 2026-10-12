package com.colonelpanic.roborock.api

import org.json.JSONArray
import org.json.JSONObject

enum class CleanMode(
    val wire: String,
) {
    VACUUM("vacuum"),
    VACUUM_AND_MOP("vacuum_and_mop"),
    MOP("mop"),
}

enum class Suction(
    val wire: String,
    val fanPower: Int,
) {
    QUIET("quiet", 101),
    BALANCED("balanced", 102),
    TURBO("turbo", 103),
    MAX("max", 104),
    MAX_PLUS("max_plus", 108),
}

enum class Water(
    val wire: String,
    val code: Int,
) {
    LOW("low", 201),
    MEDIUM("medium", 202),
    HIGH("high", 203),
}

data class Room(
    val segment: Int,
    val name: String,
    val aliases: List<String> = emptyList(),
)

data class CleanRequest(
    /** Empty means the whole home. */
    val segments: List<Int>,
    val mode: CleanMode,
    val suction: Suction,
    val water: Water,
    val repeat: Int,
) {
    /** Commands in order; the last one starts the robot. */
    fun commands(): List<Pair<String, Any>> {
        val setup =
            when (mode) {
                CleanMode.VACUUM -> {
                    listOf(
                        "set_water_box_custom_mode" to JSONArray().put(WATER_OFF),
                        "set_custom_mode" to JSONArray().put(suction.fanPower),
                    )
                }

                CleanMode.VACUUM_AND_MOP -> {
                    listOf("set_clean_motor_mode" to JSONArray().put(motorMode(suction.fanPower)))
                }

                CleanMode.MOP -> {
                    listOf("set_clean_motor_mode" to JSONArray().put(motorMode(FAN_OFF)))
                }
            }
        val start =
            if (segments.isEmpty()) {
                "app_start" to JSONArray()
            } else {
                "app_segment_clean" to JSONArray().put(JSONObject().put("segments", JSONArray(segments)).put("repeat", repeat))
            }
        return setup + start
    }

    private fun motorMode(fanPower: Int) = JSONObject().put("fan_power", fanPower).put("water_box_mode", water.code)

    companion object {
        const val WATER_OFF = 200
        const val FAN_OFF = 105
    }
}

object Cleaning {
    /**
     * Room lines look like `4 = Kitchen, dining`: segment id, display name,
     * then optional aliases. Blank lines and `#` comments are ignored.
     */
    fun parseRooms(text: String): List<Room> =
        text.lines().mapIndexedNotNull { index, raw ->
            val line = raw.substringBefore('#').trim()
            if (line.isEmpty()) return@mapIndexedNotNull null
            val (id, names) =
                line.split('=', limit = 2).takeIf { it.size == 2 }
                    ?: throw IllegalArgumentException("Room line ${index + 1} needs `segment = Name`.")
            val segment =
                id.trim().toIntOrNull()
                    ?: throw IllegalArgumentException("Room line ${index + 1}: '${id.trim()}' is not a segment number.")
            val parts = names.split(',').map { it.trim() }.filter { it.isNotEmpty() }
            if (parts.isEmpty()) throw IllegalArgumentException("Room line ${index + 1} has no name.")
            Room(segment, parts.first(), parts.drop(1))
        }

    fun formatRooms(rooms: List<Room>): String =
        rooms.joinToString("\n") { room -> (listOf(room.name) + room.aliases).joinToString(", ", prefix = "${room.segment} = ") }

    /** Resolves names, aliases or segment numbers; unmatched entries are returned separately. */
    fun resolveRooms(
        requested: List<String>,
        rooms: List<Room>,
    ): Pair<List<Room>, List<String>> {
        val resolved = mutableListOf<Room>()
        val unknown = mutableListOf<String>()
        for (entry in requested) {
            val key = normalize(entry)
            val match =
                rooms.firstOrNull { it.segment.toString() == entry.trim() }
                    ?: rooms.firstOrNull { room -> (listOf(room.name) + room.aliases).any { normalize(it) == key } }
                    ?: rooms
                        .filter { room ->
                            (listOf(room.name) + room.aliases).any {
                                normalize(it).contains(key) ||
                                    key.contains(normalize(it))
                            }
                        }.singleOrNull()
            if (match == null) {
                unknown += entry
            } else if (match !in resolved) {
                resolved += match
            }
        }
        return resolved to unknown
    }

    /** Segments from `get_multi_maps_list`, keeping [known] names and naming new ones "Room N". */
    fun roomsFromMaps(
        maps: Any,
        known: List<Room>,
    ): List<Room> {
        val info = (maps as? org.json.JSONArray)?.optJSONObject(0) ?: maps as? JSONObject ?: return known
        val found = sortedSetOf<Int>()
        val names = mutableMapOf<Int, String>()
        info.optJSONArray("map_info")?.let { mapInfo ->
            for (i in 0 until mapInfo.length()) {
                val rooms = mapInfo.getJSONObject(i).optJSONArray("rooms") ?: continue
                for (j in 0 until rooms.length()) {
                    val room = rooms.getJSONObject(j)
                    val id = room.optInt("id", -1).takeIf { it >= 0 } ?: continue
                    found += id
                    room.optString("iot_name").takeIf { it.isNotBlank() && it != "Room" }?.let { names[id] = it }
                }
            }
        }
        val bySegment = known.associateBy { it.segment }
        return found.map { bySegment[it] ?: Room(it, names[it] ?: "Room $it") } + known.filter { it.segment !in found }
    }

    private fun normalize(text: String) = text.lowercase().filter { it.isLetterOrDigit() }

    fun mode(wire: String?): CleanMode? = if (wire == null) CleanMode.VACUUM_AND_MOP else CleanMode.entries.firstOrNull { it.wire == wire }

    fun suction(wire: String?): Suction? = if (wire == null) Suction.BALANCED else Suction.entries.firstOrNull { it.wire == wire }

    fun water(wire: String?): Water? = if (wire == null) Water.HIGH else Water.entries.firstOrNull { it.wire == wire }

    private val ACTIVE_STATES = setOf(5, 11, 16, 17, 18)

    /** Picks the resume command for the unfinished clean `in_cleaning` reports. */
    fun resumeCommand(status: JSONObject): Pair<List<Pair<String, Any>>, String> {
        val state = status.optInt("state")
        if (state in ACTIVE_STATES) throw IllegalStateException("The robot is already ${STATES[state]}.")
        return when (status.optInt("in_cleaning")) {
            1 -> listOf<Pair<String, Any>>("app_start" to JSONArray()) to "Resumed the whole-home clean."

            2 -> listOf<Pair<String, Any>>("resume_zoned_clean" to JSONArray()) to "Resumed the zone clean."

            3 -> listOf<Pair<String, Any>>("resume_segment_clean" to JSONArray()) to "Resumed the room clean."

            else -> throw IllegalStateException(
                "The robot has no unfinished clean to resume (it is ${STATES[state] ?: "in state $state"}).",
            )
        }
    }

    private val STATES =
        mapOf(
            1 to "starting",
            2 to "charger disconnected",
            3 to "idle",
            4 to "remote control",
            5 to "cleaning",
            6 to "returning to the dock",
            7 to "manual mode",
            8 to "charging",
            9 to "charging problem",
            10 to "paused",
            11 to "spot cleaning",
            12 to "in error",
            13 to "shutting down",
            14 to "updating",
            15 to "docking",
            16 to "going to a target",
            17 to "zoned cleaning",
            18 to "cleaning rooms",
            22 to "emptying the bin",
            23 to "washing the mop",
            25 to "washing the mop",
            26 to "going to wash the mop",
            28 to "in a call",
            29 to "mapping",
            32 to "patrolling",
            100 to "fully charged",
        )

    fun describeStatus(status: JSONObject): Pair<String, JSONObject> {
        val stateCode = status.optInt("state")
        val state = STATES[stateCode] ?: "state $stateCode"
        val fan =
            Suction.entries.firstOrNull { it.fanPower == status.optInt("fan_power") }?.wire
                ?: if (status.optInt("fan_power") == CleanRequest.FAN_OFF) "off" else "code ${status.optInt("fan_power")}"
        val water =
            Water.entries.firstOrNull { it.code == status.optInt("water_box_mode") }?.wire
                ?: if (status.optInt("water_box_mode") == CleanRequest.WATER_OFF) "off" else "code ${status.optInt("water_box_mode")}"
        val structured =
            JSONObject()
                .put("state", state)
                .put("stateCode", stateCode)
                .put("battery", status.optInt("battery"))
                .put("suction", fan)
                .put("water", water)
                .put("errorCode", status.optInt("error_code"))
                .put("inCleaning", status.optInt("in_cleaning") != 0)
                .put("cleanPercent", status.optInt("clean_percent"))
                .put("cleanMinutes", status.optInt("clean_time") / 60)
                .put("cleanAreaSquareMeters", status.optLong("clean_area") / 1_000_000.0)
        val text =
            buildString {
                append("The robot is $state with ${status.optInt("battery")}% battery")
                if (status.optInt("in_cleaning") != 0) append(", ${status.optInt("clean_percent")}% through the current clean")
                append(". Suction $fan, water $water.")
                if (status.optInt("error_code") != 0) append(" Error code ${status.optInt("error_code")}.")
            }
        return text to structured
    }
}
