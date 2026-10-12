package com.colonelpanic.roborock.ui

import android.app.Activity
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.view.WindowInsets
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.colonelpanic.roborock.Store
import com.colonelpanic.roborock.api.Account
import com.colonelpanic.roborock.api.Cleaning
import com.colonelpanic.roborock.api.Presets
import com.colonelpanic.roborock.api.RoborockCloud
import com.colonelpanic.roborock.api.Robot
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

/** Sign-in, room names, presets and a status check. EVA does the rest through the extension service. */
class MainActivity : Activity() {
    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var store: Store
    private var cloud: RoborockCloud? = null

    private lateinit var accountText: TextView
    private lateinit var email: EditText
    private lateinit var code: EditText
    private lateinit var rooms: EditText
    private lateinit var presets: EditText
    private lateinit var output: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = Store(this)
        val column =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                val pad = dp(16)
                setPadding(pad, pad, pad, pad)
            }
        accountText = column.text("")
        column.heading("Sign in")
        email = column.field("Roborock account email", InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS)
        column.button("Email me a code") { requestCode() }
        code = column.field("Code from the email", InputType.TYPE_CLASS_NUMBER)
        column.button("Sign in") { signIn() }
        column.button("Import desktop session") { importSession() }
        column.text(
            "Imports python-roborock's ~/.roborock pushed to ${importFile().path} (optionally with \"rooms\" and \"presets\" fields). The file is deleted after import.",
        )

        column.heading("Rooms")
        column.text("One per line: segment = Name, alias, alias")
        rooms = column.field("4 = Kitchen, dining", InputType.TYPE_TEXT_FLAG_MULTI_LINE, lines = 6)
        column.button("Find rooms on the robot") { findRooms() }
        column.button("Save rooms") { saveRooms() }

        column.heading("Presets")
        column.text("JSON: name → rooms (empty for whole home), mode (vacuum, vacuum_and_mop, mop), suction, water, repeat.")
        presets = column.field("", InputType.TYPE_TEXT_FLAG_MULTI_LINE, lines = 8)
        column.button("Save presets") { savePresets() }

        column.heading("Check")
        column.button("Robot status") { status() }
        output = column.text("")
        output.setTextIsSelectable(true)

        val scroll = ScrollView(this).apply { addView(column) }
        scroll.setOnApplyWindowInsetsListener { view, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsets.CONSUMED
        }
        setContentView(scroll)
        load()
    }

    override fun onDestroy() {
        worker.shutdown()
        super.onDestroy()
    }

    private fun load() {
        val account = store.account
        accountText.text =
            account?.let {
                "Signed in${if (it.email.isNotEmpty()) " as ${it.email}" else ""}. Robot: ${it.device.name} (${it.device.model})."
            }
                ?: "Not signed in."
        if (email.text.isEmpty()) email.setText(account?.email.orEmpty())
        rooms.setText(store.roomsText)
        presets.setText(store.presetsText)
    }

    private fun requestCode() {
        val address = email.text.toString().trim()
        if (address.isEmpty()) return show("Enter the Roborock account email first.")
        background("Requesting a code…") {
            RoborockCloud(address).also { cloud = it }.requestCode()
            "A code was sent to $address."
        }
    }

    private fun signIn() {
        val address = email.text.toString().trim()
        val entered = code.text.toString().trim()
        val pending = cloud
        if (pending == null || entered.isEmpty()) return show("Request a code first, then enter it.")
        background("Signing in…") {
            val account = pending.signIn(entered)
            store.account = account
            cloud = null
            val found = discoverRooms(account)
            "Signed in as $address. Robot: ${account.device.name}. $found"
        }
    }

    private fun importSession() {
        background("Importing…") {
            val file = importFile()
            if (!file.exists()) throw IllegalStateException("No file at ${file.path}.")
            try {
                val json = JSONObject(file.readText())
                val account = Account.fromPythonRoborockCache(json)
                store.account = account
                json.optString("rooms").takeIf { it.isNotBlank() }?.let { store.roomsText = Cleaning.formatRooms(Cleaning.parseRooms(it)) }
                json.optJSONObject("presets")?.let { store.presetsText = it.toString(2) }
                "Imported the session for ${account.device.name}. ${if (store.roomsText.isBlank()) discoverRooms(account) else ""}"
            } finally {
                file.delete()
            }
        }
    }

    private fun findRooms() {
        val account = store.account ?: return show("Sign in first.")
        background("Asking the robot for its rooms…") { discoverRooms(account) }
    }

    /** Adds segments the robot reports that the list lacks; keeps existing names. */
    private fun discoverRooms(account: Account): String {
        val known = Cleaning.parseRooms(store.roomsText)
        val deadline = System.currentTimeMillis() + 20_000
        val maps = Robot(account).open(deadline).use { it.call("get_multi_maps_list", JSONArray(), deadline) }
        val merged = Cleaning.roomsFromMaps(maps, known)
        store.roomsText = Cleaning.formatRooms(merged)
        val added = merged.size - known.size
        return if (added > 0) "Found $added new room segment(s); name them under Rooms." else "The room list matches the robot."
    }

    private fun saveRooms() {
        try {
            store.roomsText = Cleaning.formatRooms(Cleaning.parseRooms(rooms.text.toString()))
            load()
            show("Rooms saved.")
        } catch (e: IllegalArgumentException) {
            show(e.message.orEmpty())
        }
    }

    private fun savePresets() {
        val text = presets.text.toString()
        try {
            val parsed = Presets.parse(text)
            store.presetsText = if (text.isBlank()) "" else JSONObject(text).toString(2)
            load()
            show("Saved ${parsed.size} preset(s).")
        } catch (e: org.json.JSONException) {
            show("Presets are not valid JSON: ${e.message}")
        }
    }

    private fun status() {
        val account = store.account ?: return show("Sign in first.")
        background("Asking the robot…") {
            val deadline = System.currentTimeMillis() + 20_000
            val result = Robot(account).open(deadline).use { it.call("get_status", null, deadline) }
            val status = (result as? JSONArray)?.optJSONObject(0) ?: result as JSONObject
            Cleaning.describeStatus(status).first
        }
    }

    private fun background(
        progress: String,
        work: () -> String,
    ) {
        show(progress)
        worker.execute {
            val message =
                try {
                    work()
                } catch (e: Exception) {
                    "Failed: ${e.message ?: e.javaClass.simpleName}"
                }
            runOnUiThread {
                load()
                show(message)
            }
        }
    }

    private fun show(message: String) {
        output.text = message
    }

    private fun importFile() = File(getExternalFilesDir(null), "import.json")

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun LinearLayout.heading(label: String) =
        addView(
            TextView(context).apply {
                text = label
                textSize = 20f
                setTypeface(typeface, Typeface.BOLD)
                setPadding(0, dp(20), 0, dp(4))
            },
        )

    private fun LinearLayout.text(label: String): TextView = TextView(context).apply { text = label }.also { addView(it) }

    private fun LinearLayout.field(
        hintText: String,
        type: Int,
        lines: Int = 1,
    ): EditText =
        EditText(context)
            .apply {
                hint = hintText
                if (lines > 1) {
                    inputType = InputType.TYPE_CLASS_TEXT or type or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                    minLines = lines
                    typeface = Typeface.MONOSPACE
                    textSize = 13f
                } else {
                    inputType = if (type == InputType.TYPE_CLASS_NUMBER) type else InputType.TYPE_CLASS_TEXT or type
                }
            }.also { addView(it) }

    private fun LinearLayout.button(
        label: String,
        action: () -> Unit,
    ) = addView(Button(context).apply { text = label }.also { it.setOnClickListener { _: View -> action() } })
}
