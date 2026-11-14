package org.voiceassistant

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.widget.ImageButton
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.inputmethod.InputMethodManager
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

object Prefs {
    private fun p(c: Context) = c.getSharedPreferences("assistant", Context.MODE_PRIVATE)
    fun passphrase(c: Context): String? = p(c).getString("key", null)
    fun setPassphrase(c: Context, v: String) = p(c).edit().putString("key", v).apply()
    fun voiceThreshold(c: Context): Float? = p(c).getFloat("voiceThreshold", -1f).takeIf { it > 0f }
    fun setVoiceThreshold(c: Context, v: Float) = p(c).edit().putFloat("voiceThreshold", v).apply()
}

class MainActivity : Activity() {
    private lateinit var orb: OrbView
    private lateinit var status: TextView
    private lateinit var toggle: ImageButton
    private lateinit var phraseRow: LinearLayout
    private lateinit var battery: Button
    private lateinit var dnd: Button
    private lateinit var reply: TextView
    private lateinit var typeRow: LinearLayout
    private lateinit var typeBox: EditText

    private val cyan = Color.rgb(63, 216, 255)
    private val dim = Color.argb(150, 160, 210, 235)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(2, 6, 12)
        window.navigationBarColor = Color.rgb(2, 6, 12)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        orb = OrbView(this).apply { setOnClickListener { talk() } }
        val title = TextView(this).apply { text = Persona.NAME.uppercase().toCharArray().joinToString(" "); setTextColor(cyan); textSize = 13f; gravity = Gravity.CENTER; letterSpacing = 0.4f }
        status = TextView(this).apply { setTextColor(dim); textSize = 13f; gravity = Gravity.CENTER; letterSpacing = 0.1f }

        val phrase = EditText(this).apply {
            hint = "Access phrase"; setHintTextColor(dim); setTextColor(Color.WHITE)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            imeOptions = EditorInfo.IME_ACTION_DONE; isSingleLine = true
        }
        val save = Button(this).apply { text = "Save" }
        phraseRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(phrase, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)); addView(save)
        }
        save.setOnClickListener {
            val v = phrase.text.toString().trim()
            if (v.isNotEmpty()) { Prefs.setPassphrase(this, v); phrase.setText(""); refresh(); start() }
        }

        // Small power icon instead of a big button: the lock-screen card has Stop too, and opening the app turns the assistant on.
        toggle = iconButton(R.drawable.ic_power) { if (AssistantService.running) stop() else start() }
        // Only needed once; shown only while Samsung's battery saver could still kill the listener.
        battery = Button(this).apply { text = "Keep running in background"; setOnClickListener { askBatteryExemption() } }
        // Sleep mode needs this once; shown only until it's granted.
        dnd = Button(this).apply { text = "Allow Do Not Disturb"; setOnClickListener { startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)) } }

        // What the assistant last said, so a typed (or missed) answer can be read as well as heard.
        reply = TextView(this).apply { setTextColor(Color.rgb(207, 239, 255)); textSize = 15f; gravity = Gravity.CENTER; setPadding(8, 0, 8, 16) }

        // Typing stays hidden until the keyboard button is tapped, and hides again after sending.
        typeBox = EditText(this).apply {
            hint = "Type to ${Persona.NAME}"; setHintTextColor(dim); setTextColor(Color.WHITE)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            imeOptions = EditorInfo.IME_ACTION_SEND; isSingleLine = true
            // IME_NULL covers a hardware/injected Enter key; SEND covers the keyboard's Send key.
            setOnEditorActionListener { _, id, _ -> if (id == EditorInfo.IME_ACTION_SEND || id == EditorInfo.IME_NULL) { sendTyped(); true } else false }
        }
        val send = Button(this).apply { text = "Send"; setOnClickListener { sendTyped() } }
        typeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; visibility = View.GONE
            addView(typeBox, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)); addView(send)
        }
        val keyboard = iconButton(R.drawable.ic_keyboard) { if (typeRow.visibility == View.VISIBLE) hideTyping() else showTyping() }
            .apply { contentDescription = "Type instead of talking"; imageTintList = ColorStateList.valueOf(Color.rgb(207, 239, 255)) }
        val commands = iconButton(R.drawable.ic_list) { startActivity(Intent(this, CommandsActivity::class.java)) }
            .apply { contentDescription = "What you can say"; imageTintList = ColorStateList.valueOf(Color.rgb(207, 239, 255)) }
        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
            addView(toggle, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
            addView(keyboard, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = 32 })
            addView(commands, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = 32 })
        }

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 96, 48, 64)
            addView(title, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            addView(status, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = 12 })
            addView(orb, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
            addView(reply, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            addView(phraseRow, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            addView(typeRow, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            addView(controls, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = 16 })
            addView(battery, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = 8 })
            addView(dnd, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = 8 })
        }
        // Android 15+ draws edge-to-edge and ignores adjustResize, so lift the layout above the
        // keyboard (and the system bars) ourselves; otherwise the text box sits behind the keyboard.
        column.setOnApplyWindowInsetsListener { v, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars())
            val ime = insets.getInsets(WindowInsets.Type.ime())
            v.setPadding(48, bars.top + 48, 48, maxOf(bars.bottom, ime.bottom) + 32)
            insets
        }
        setContentView(FrameLayout(this).apply { setBackgroundColor(Color.rgb(2, 6, 12)); addView(column) })

        val needed = arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS, Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR, Manifest.permission.READ_CONTACTS, Manifest.permission.SEND_SMS, Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.READ_CALL_LOG)
            .filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (needed.isNotEmpty()) requestPermissions(needed.toTypedArray(), 1) else if (Prefs.passphrase(this) != null) start()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (Prefs.passphrase(this) != null) start() else refresh()
    }

    override fun onResume() {
        super.onResume()
        AssistantService.onState = { refresh() }
        // Opening the assistant means you want the assistant: start listening if it was stopped (e.g. from the lock screen).
        if (!AssistantService.running) start()
        refresh()
    }

    override fun onPause() {
        AssistantService.onState = null
        super.onPause()
    }

    private fun refresh() {
        val hasKey = Prefs.passphrase(this) != null
        phraseRow.visibility = if (hasKey) android.view.View.GONE else android.view.View.VISIBLE
        val s = AssistantService.state
        status.text = when {
            !hasKey -> "Enter your access phrase to begin"
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED -> "Microphone permission needed"
            else -> s
        }
        toggle.imageTintList = ColorStateList.valueOf(if (AssistantService.running) cyan else dim)
        toggle.contentDescription = if (AssistantService.running) "Stop listening" else "Start listening"
        battery.visibility = if (getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)) View.GONE else View.VISIBLE
        dnd.visibility = if (SleepMode.canControlDnd(this)) View.GONE else View.VISIBLE
        reply.text = AssistantService.lastReply.orEmpty()
        reply.visibility = if (AssistantService.lastReply.isNullOrBlank()) View.GONE else View.VISIBLE
        orb.mode = when {
            s.startsWith("Listening for") -> OrbView.Mode.IDLE
            s == "Listening" || s == "Anything else?" -> OrbView.Mode.LISTENING
            s == "Thinking" -> OrbView.Mode.THINKING
            s == "Speaking" -> OrbView.Mode.SPEAKING
            else -> OrbView.Mode.OFF
        }
    }

    private fun start() {
        if (Prefs.passphrase(this) == null) return
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return
        startForegroundService(Intent(this, AssistantService::class.java))
        status.postDelayed({ refresh() }, 300)
    }

    private fun stop() {
        startService(Intent(this, AssistantService::class.java).setAction(AssistantService.ACTION_STOP))
        status.postDelayed({ refresh() }, 300)
    }

    private fun talk() {
        if (!AssistantService.running) { start(); return }
        startService(Intent(this, AssistantService::class.java).setAction(AssistantService.ACTION_TALK))
    }

    private fun iconButton(icon: Int, onTap: () -> Unit) = ImageButton(this).apply {
        setImageResource(icon)
        setBackgroundResource(R.drawable.icon_btn_bg)
        minimumWidth = 168; minimumHeight = 120
        setPadding(48, 32, 48, 32)
        setOnClickListener { onTap() }
    }

    private fun showTyping() {
        typeRow.visibility = View.VISIBLE
        typeBox.requestFocus()
        getSystemService(InputMethodManager::class.java).showSoftInput(typeBox, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun hideTyping() {
        getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(typeBox.windowToken, 0)
        typeBox.setText("")
        typeRow.visibility = View.GONE
    }

    private fun sendTyped() {
        val text = typeBox.text.toString().trim()
        if (text.isEmpty()) return
        if (!AssistantService.running) start()
        startService(Intent(this, AssistantService::class.java).setAction(AssistantService.ACTION_ASK).putExtra("text", text))
        hideTyping()
    }

    @Deprecated("Activity back handling")
    override fun onBackPressed() {
        if (typeRow.visibility == View.VISIBLE) hideTyping() else @Suppress("DEPRECATION") super.onBackPressed()
    }

    private fun askBatteryExemption() {
        val pm = getSystemService(PowerManager::class.java)
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        } else {
            status.text = "Already allowed to run in the background"
        }
    }
}
