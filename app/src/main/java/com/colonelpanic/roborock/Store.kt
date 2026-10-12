package com.colonelpanic.roborock

import android.content.Context
import android.os.UserManager
import com.colonelpanic.roborock.api.Account
import com.colonelpanic.roborock.eva.Capabilities
import org.json.JSONObject

/** App-private settings: the signed-in account, room names and presets. */
class Store(
    context: Context,
) {
    private val app = context.applicationContext
    private val prefs by lazy { app.getSharedPreferences("roborock", Context.MODE_PRIVATE) }

    var account: Account?
        get() = prefs.getString(KEY_ACCOUNT, null)?.let { Account.fromJson(JSONObject(it)) }
        set(value) = prefs.edit().putString(KEY_ACCOUNT, value?.toJson()?.toString()).apply()

    var roomsText: String
        get() = prefs.getString(KEY_ROOMS, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_ROOMS, value).apply()

    var presetsText: String
        get() = prefs.getString(KEY_PRESETS, null) ?: com.colonelpanic.roborock.api.Presets.EXAMPLE
        set(value) = prefs.edit().putString(KEY_PRESETS, value).apply()

    /** Credential-encrypted storage is unreadable before the first unlock after boot. */
    fun configuration(): Capabilities.Configuration {
        val unlocked = app.getSystemService(UserManager::class.java)?.isUserUnlocked ?: true
        if (!unlocked) return Capabilities.Configuration(null, "", "")
        return Capabilities.Configuration(account, roomsText, presetsText)
    }

    companion object {
        private const val KEY_ACCOUNT = "account"
        private const val KEY_ROOMS = "rooms"
        private const val KEY_PRESETS = "presets"
    }
}
