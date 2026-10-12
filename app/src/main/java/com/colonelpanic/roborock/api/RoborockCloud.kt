package com.colonelpanic.roborock.api

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Roborock's account web API: email-code sign-in and home data. Ported from
 * python-roborock's web_api.py. One instance spans one sign-in attempt, because
 * the code request and the login must present the same client identity.
 */
class RoborockCloud(
    private val email: String,
) {
    private val random = SecureRandom()
    private val deviceIdentifier = randomToken(16)
    private var login: LoginInfo? = null

    private data class LoginInfo(
        val baseUrl: String,
        val country: String,
        val countryCode: String,
    )

    private val clientId: String
        get() = Base64.getEncoder().encodeToString(V1Codec.md5((email + deviceIdentifier).toByteArray()))

    fun requestCode() {
        val info = loginInfo()
        val response =
            request(
                "POST",
                info.baseUrl + "/api/v4/email/code/send",
                form = mapOf("email" to email, "type" to "login", "platform" to ""),
                headers = mapOf("header_clientid" to clientId, "header_clientlang" to "en"),
            )
        when (response.optInt("code")) {
            200 -> Unit
            2008 -> throw RoborockException("Roborock has no account for $email.")
            9002 -> throw RoborockException("Too many code requests; wait a while and try again.")
            else -> throw apiError("Requesting a sign-in code", response)
        }
    }

    /** Signs in with the emailed code and returns the account for the account's one robot. */
    fun signIn(code: String): Account {
        val info = loginInfo()
        val ks = (1..16).map { ALPHANUMERIC[random.nextInt(ALPHANUMERIC.length)] }.joinToString("")
        val sign = request("POST", info.baseUrl + "/api/v3/key/sign?s=$ks", headers = mapOf("header_clientid" to clientId))
        val k = sign.optJSONObject("data")?.optString("k").orEmpty()
        if (k.isEmpty()) throw apiError("Signing the login request", sign)
        val response =
            request(
                "POST",
                info.baseUrl + "/api/v4/auth/email/login/code",
                form =
                    mapOf(
                        "country" to info.country,
                        "countryCode" to info.countryCode,
                        "email" to email,
                        "code" to code.trim(),
                        "majorVersion" to "14",
                        "minorVersion" to "0",
                    ),
                headers =
                    mapOf(
                        "header_clientid" to clientId,
                        "x-mercy-ks" to ks,
                        "x-mercy-k" to k,
                        "header_clientlang" to "en",
                        "header_appversion" to "4.54.02",
                        "header_phonesystem" to "iOS",
                        "header_phonemodel" to "iPhone16,1",
                    ),
            )
        when (response.optInt("code")) {
            200 -> Unit
            2018 -> throw RoborockException("That code is wrong or expired; request a new one.")
            3009, 3006 -> throw RoborockException("Accept Roborock's current user agreement in the Roborock app, then sign in again.")
            else -> throw apiError("Signing in", response)
        }
        val userData = response.getJSONObject("data")
        val token = userData.getString("token")
        val rriot = Account.rriotFromJson(userData.getJSONObject("rriot"))
        val homeData = homeData(info.baseUrl, token, rriot)
        return Account(email, token, rriot, Account.pickDevice(homeData))
    }

    /** Cloud room ids to names; often empty, in which case rooms are named on the phone. */
    fun roomNames(account: Account): Map<String, String> {
        val info = loginInfo()
        val home = homeData(info.baseUrl, account.token, account.rriot)
        val names = mutableMapOf<String, String>()
        home.optJSONArray("rooms")?.let { rooms ->
            for (i in 0 until rooms.length()) {
                val room = rooms.getJSONObject(i)
                names[room.optString("id")] = room.optString("name")
            }
        }
        return names
    }

    private fun homeData(
        baseUrl: String,
        token: String,
        rriot: Account.Rriot,
    ): JSONObject {
        val detail =
            request("GET", "$baseUrl/api/v1/getHomeDetail", headers = mapOf("header_clientid" to clientId, "Authorization" to token))
        if (detail.optInt("code") != 200) throw apiError("Reading the home", detail)
        val homeId = detail.getJSONObject("data").get("rrHomeId").toString()
        val path = "/v3/user/homes/$homeId"
        val home = request("GET", rriot.apiUrl.trimEnd('/') + path, headers = mapOf("Authorization" to hawk(rriot, path)))
        if (!home.optBoolean("success")) throw apiError("Reading home data", home)
        return home.getJSONObject("result")
    }

    private fun loginInfo(): LoginInfo {
        login?.let { return it }
        for (base in BASE_URLS) {
            val response =
                try {
                    request("POST", "$base/api/v1/getUrlByEmail?email=${encode(email)}&needtwostepauth=false")
                } catch (e: IOException) {
                    continue
                }
            if (response.optInt("code") != 200) throw apiError("Looking up the account region", response)
            val data = response.getJSONObject("data")
            val country = data.optString("country")
            val countryCode = data.opt("countrycode")?.toString().orEmpty()
            if (country.isNotEmpty() || countryCode.isNotEmpty()) {
                return LoginInfo(data.getString("url"), country, countryCode).also { login = it }
            }
        }
        throw RoborockException("No Roborock region knows $email; check the address.")
    }

    private fun hawk(
        rriot: Account.Rriot,
        path: String,
    ): String {
        val timestamp = System.currentTimeMillis() / 1000
        val nonce = randomToken(6)
        val prestr = listOf(rriot.u, rriot.s, nonce, timestamp.toString(), V1Codec.md5Hex(path), "", "").joinToString(":")
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(rriot.h.toByteArray(), "HmacSHA256")) }
        val digest = Base64.getEncoder().encodeToString(mac.doFinal(prestr.toByteArray()))
        return "Hawk id=\"${rriot.u}\",s=\"${rriot.s}\",ts=\"$timestamp\",nonce=\"$nonce\",mac=\"$digest\""
    }

    private fun randomToken(bytes: Int): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(bytes).also { random.nextBytes(it) })

    private fun request(
        method: String,
        url: String,
        form: Map<String, String>? = null,
        headers: Map<String, String> = emptyMap(),
    ): JSONObject {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.connectTimeout = 15_000
            connection.readTimeout = 20_000
            headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
            if (form != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                val body = form.entries.joinToString("&") { encode(it.key) + "=" + encode(it.value) }
                connection.outputStream.use { it.write(body.toByteArray()) }
            } else if (method == "POST") {
                connection.doOutput = true
                connection.outputStream.use { }
            }
            val status = connection.responseCode
            val stream = if (status < 400) connection.inputStream else connection.errorStream
            val text = stream?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
            return try {
                JSONObject(text)
            } catch (e: org.json.JSONException) {
                throw IOException("Roborock returned HTTP $status with a non-JSON body.")
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun apiError(
        action: String,
        response: JSONObject,
    ): RoborockException {
        val code = response.opt("code")
        val message = response.optString("msg").ifEmpty { response.optString("message") }
        return RoborockException("$action failed: ${message.ifEmpty { "no message" }} (code $code).")
    }

    private fun encode(value: String) = URLEncoder.encode(value, "UTF-8")

    companion object {
        private const val ALPHANUMERIC = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
        private val BASE_URLS =
            listOf(
                "https://usiot.roborock.com",
                "https://euiot.roborock.com",
                "https://cniot.roborock.com",
                "https://ruiot.roborock.com",
            )
    }
}
