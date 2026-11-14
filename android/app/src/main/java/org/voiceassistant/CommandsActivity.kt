package org.voiceassistant

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowInsets
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONObject

/** Everything you can say to the assistant, read from the same commands.json the brain uses. */
class CommandsActivity : Activity() {
    private val cyan = Color.rgb(63, 216, 255)
    private val text = Color.rgb(207, 239, 255)
    private val dim = Color.argb(150, 160, 210, 235)
    private val bg = Color.rgb(2, 6, 12)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = bg; window.navigationBarColor = bg

        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        list.addView(label("C O M M A N D S", cyan, 13f).apply { letterSpacing = 0.4f })
        list.addView(label("Say \"${Persona.WAKE_PHRASE}\", then any of these. Dimmed ones are coming soon.", dim, 13f), lp(top = 8))

        val root = JSONObject(assets.open("commands.json").bufferedReader().use { it.readText() })
        val sections = root.getJSONArray("sections")
        for (i in 0 until sections.length()) {
            val sec = sections.getJSONObject(i)
            list.addView(label(sec.getString("title").uppercase(), cyan, 12f).apply { letterSpacing = 0.15f }, lp(top = 40))
            val cmds = sec.getJSONArray("commands")
            for (j in 0 until cmds.length()) {
                val c = cmds.getJSONObject(j)
                val live = c.optString("status") == "live"
                val say = c.getString("say")
                val quoted = if (say.startsWith("(") || say.startsWith("Tap")) say else "“$say”"
                val row = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; alpha = if (live) 1f else 0.45f }
                row.addView(label(quoted + if (live) "" else "   · coming soon", text, 16f).apply { setTypeface(typeface, Typeface.BOLD) })
                row.addView(label(c.getString("does"), dim, 14f), lp(top = 2))
                list.addView(row, lp(top = 18))
            }
        }

        val scroll = ScrollView(this).apply { setBackgroundColor(bg); addView(list) }
        scroll.setOnApplyWindowInsetsListener { _, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars())
            list.setPadding(56, bars.top + 48, 56, bars.bottom + 64)
            insets
        }
        setContentView(scroll)
    }

    private fun label(s: String, color: Int, size: Float) = TextView(this).apply { text = s; setTextColor(color); textSize = size }
    private fun lp(top: Int) = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = top }
}
