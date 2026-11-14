package org.voiceassistant

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowInsets
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.concurrent.thread

/** "Here's what's on your Wi-Fi": scan, then connect what the assistant can drive. */
class HomeActivity : Activity() {
    private val cyan = Color.rgb(63, 216, 255)
    private val text = Color.rgb(207, 239, 255)
    private val dim = Color.argb(150, 160, 210, 235)
    private val bg = Color.rgb(2, 6, 12)
    private val main = Handler(Looper.getMainLooper())
    private lateinit var list: LinearLayout
    private lateinit var status: TextView
    private lateinit var rescan: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = bg; window.navigationBarColor = bg
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        column.addView(label("H O M E   D E V I C E S", cyan, 13f).apply { letterSpacing = 0.4f })
        column.addView(label("What ${Persona.NAME} found on your Wi-Fi. Nothing is connected until you tap Connect.", dim, 13f), lp(8))
        status = label("", dim, 13f)
        column.addView(status, lp(24))
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        column.addView(list, lp(8))
        rescan = Button(this).apply { text = "Scan again"; setOnClickListener { scan() } }
        column.addView(rescan, lp(32))
        column.addView(label("Don't see something? It may be on another network (many routers keep smart devices on a separate one), " +
            "switched off, or not announcing itself. Supported brands grow over time.", dim, 12f), lp(16))

        val scroll = ScrollView(this).apply { setBackgroundColor(bg); addView(column) }
        scroll.setOnApplyWindowInsetsListener { _, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars())
            column.setPadding(56, bars.top + 48, 56, bars.bottom + 64)
            insets
        }
        setContentView(scroll)
        val last = HomeScan.last(this)
        if (last.isEmpty()) scan() else { show(last); status.text = "From your last scan." }
    }

    private fun scan() {
        rescan.isEnabled = false; status.text = "Looking around your Wi-Fi…"; list.removeAllViews()
        thread(name = "assistant-home-scan") {
            val found = runCatching { HomeScan.scan(this) }.getOrDefault(emptyList())
            main.post {
                rescan.isEnabled = true
                status.text = when (found.size) { 0 -> "Nothing found. Are you on your home Wi-Fi?"; 1 -> "Found 1 device."; else -> "Found ${found.size} devices." }
                show(found)
            }
        }
    }

    private fun show(ds: List<HomeScan.Device>) {
        list.removeAllViews()
        for ((section, items) in ds.groupBy { it.support }) {
            list.addView(label(when (section) {
                HomeScan.Support.CONNECT -> "READY TO CONNECT"
                HomeScan.Support.PLANNED -> "SUPPORT COMING"
                HomeScan.Support.LIMITED -> "FOUND, NO OPEN CONTROL"
            }, cyan, 12f).apply { letterSpacing = 0.15f }, lp(28))
            items.forEach { list.addView(row(it), lp(14)) }
        }
    }

    private fun row(d: HomeScan.Device): View {
        val r = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; alpha = if (d.support == HomeScan.Support.LIMITED) 0.6f else 1f }
        r.addView(label(d.name, text, 16f).apply { setTypeface(typeface, Typeface.BOLD) })
        r.addView(label("${d.brand} · ${d.kind} · ${d.ip}", dim, 13f), lp(2))
        r.addView(label(d.note, dim, 13f), lp(2))
        if (d.support == HomeScan.Support.CONNECT && d.brand == "Samsung") {
            val connected = SamsungTv.paired(this, d.ip)
            val b = Button(this).apply { text = if (connected) "Connected · test again" else "Connect" }
            b.setOnClickListener {
                b.isEnabled = false; b.text = "Look at the TV and press Allow…"
                thread(name = "assistant-tv-pair") {
                    val why = SamsungTv.pair(this, d.ip)
                    main.post { b.isEnabled = true; b.text = if (why == null) "Connected · test again" else "Try again"; if (why != null) status.text = why }
                }
            }
            r.addView(b, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = 6 })
        }
        return r
    }

    private fun label(s: String, color: Int, size: Float) = TextView(this).apply { text = s; setTextColor(color); textSize = size }
    private fun lp(top: Int) = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = top }
}
