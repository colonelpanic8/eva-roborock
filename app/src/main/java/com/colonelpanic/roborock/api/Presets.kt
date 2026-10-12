package com.colonelpanic.roborock.api

import org.json.JSONArray
import org.json.JSONObject

data class Preset(
    val name: String,
    /** Room names, aliases or segment numbers; empty means the whole home. */
    val rooms: List<String>,
    val mode: String?,
    val suction: String?,
    val water: String?,
    val repeat: Int,
)

object Presets {
    const val EXAMPLE =
        """{
  "whole home": {"rooms": [], "mode": "vacuum", "suction": "balanced"},
  "kitchen mop": {"rooms": ["kitchen"], "mode": "vacuum_and_mop", "suction": "max", "water": "high"}
}"""

    /** Parses `{"name": {"rooms": [...], "mode": ..., "suction": ..., "water": ..., "repeat": n}}`. */
    fun parse(text: String): List<Preset> {
        if (text.isBlank()) return emptyList()
        val json = JSONObject(text)
        return json
            .keys()
            .asSequence()
            .sorted()
            .map { name ->
                val preset = json.getJSONObject(name)
                val rooms = preset.optJSONArray("rooms") ?: JSONArray()
                Preset(
                    name = name,
                    rooms = (0 until rooms.length()).map { rooms.get(it).toString() },
                    mode = preset.optString("mode").ifEmpty { null },
                    suction = preset.optString("suction").ifEmpty { null },
                    water = preset.optString("water").ifEmpty { null },
                    repeat = preset.optInt("repeat", 1),
                )
            }.toList()
    }

    fun find(
        presets: List<Preset>,
        name: String,
    ): Preset? {
        val key = name.lowercase().filter { it.isLetterOrDigit() }
        return presets.firstOrNull { it.name.lowercase().filter { c -> c.isLetterOrDigit() } == key }
    }
}
