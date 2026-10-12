package com.colonelpanic.roborock.api

import org.json.JSONObject

/** Everything needed to reach one robot after sign-in. Held in app-private storage only. */
data class Account(
    val email: String,
    val token: String,
    val rriot: Rriot,
    val device: Device,
) {
    data class Rriot(
        val u: String,
        val s: String,
        val h: String,
        val k: String,
        val apiUrl: String,
        val mqttUrl: String,
    )

    data class Device(
        val duid: String,
        val name: String,
        val localKey: String,
        val model: String,
    )

    /** Non-secret identity of the account/robot pair, for EVA's authorization scope. */
    val scopeId: String
        get() = V1Codec.md5Hex(rriot.u + "/" + device.duid).take(12)

    fun toJson(): JSONObject =
        JSONObject()
            .put("email", email)
            .put("token", token)
            .put(
                "rriot",
                JSONObject()
                    .put("u", rriot.u)
                    .put("s", rriot.s)
                    .put("h", rriot.h)
                    .put("k", rriot.k)
                    .put("r", JSONObject().put("a", rriot.apiUrl).put("m", rriot.mqttUrl)),
            ).put(
                "device",
                JSONObject()
                    .put("duid", device.duid)
                    .put("name", device.name)
                    .put("localKey", device.localKey)
                    .put("model", device.model),
            )

    companion object {
        fun fromJson(json: JSONObject): Account {
            val device = json.getJSONObject("device")
            return Account(
                email = json.optString("email"),
                token = json.optString("token"),
                rriot = rriotFromJson(json.getJSONObject("rriot")),
                device =
                    Device(
                        duid = device.getString("duid"),
                        name = device.optString("name"),
                        localKey = device.getString("localKey"),
                        model = device.optString("model"),
                    ),
            )
        }

        fun rriotFromJson(json: JSONObject): Rriot {
            val r = json.getJSONObject("r")
            return Rriot(
                u = json.getString("u"),
                s = json.getString("s"),
                h = json.getString("h"),
                k = json.getString("k"),
                apiUrl = r.getString("a"),
                mqttUrl = r.getString("m"),
            )
        }

        /**
         * Reads python-roborock's `~/.roborock` cache, the session the desktop
         * `roborock` CLI keeps. Picks [duid] or the only device.
         */
        fun fromPythonRoborockCache(
            json: JSONObject,
            duid: String? = null,
        ): Account {
            val userData = json.getJSONObject("userData")
            val homeData =
                json.optJSONObject("cacheData")?.optJSONObject("homeData")
                    ?: throw RoborockException("The session file has no cached home data; run any roborock CLI command first.")
            return Account(
                email = json.optString("email"),
                token = userData.optString("token"),
                rriot = rriotFromJson(userData.getJSONObject("rriot")),
                device = pickDevice(homeData, duid),
            )
        }

        fun pickDevice(
            homeData: JSONObject,
            duid: String? = null,
        ): Device {
            val models = mutableMapOf<String, String>()
            homeData.optJSONArray("products")?.let { products ->
                for (i in 0 until products.length()) {
                    val product = products.getJSONObject(i)
                    models[product.optString("id")] = product.optString("model")
                }
            }
            val devices = mutableListOf<JSONObject>()
            for (key in listOf("devices", "receivedDevices")) {
                homeData.optJSONArray(key)?.let { array -> for (i in 0 until array.length()) devices += array.getJSONObject(i) }
            }
            val vacuums = devices.filter { models[it.optString("productId")]?.contains("vacuum") != false }
            val chosen =
                when {
                    duid != null -> vacuums.firstOrNull { it.optString("duid") == duid }
                    vacuums.size == 1 -> vacuums.single()
                    else -> null
                } ?: throw RoborockException(
                    "Expected one robot vacuum on the account, found ${vacuums.size}: " +
                        vacuums.joinToString { it.optString("name") },
                )
            return Device(
                duid = chosen.getString("duid"),
                name = chosen.optString("name", "Roborock"),
                localKey = chosen.getString("localKey"),
                model = models[chosen.optString("productId")].orEmpty(),
            )
        }
    }
}
