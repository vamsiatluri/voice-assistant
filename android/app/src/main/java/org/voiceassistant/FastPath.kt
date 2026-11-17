package org.voiceassistant

import android.content.Context
import android.util.Log
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.GenerativeModel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * The fast path: home commands (TV, volume, apps, car mode) understood ON THE PHONE by Gemini Nano
 * and answered with pre-recorded lines in the assistant's own voice, so they take well under a second and
 * still work when the cloud brain can't be reached. Everything else goes to the brain as before.
 *
 * Only words that could be a home command reach the local model at all (TV, volume, an app name,
 * car mode); the model then decides what, if anything, the user meant. "No" from it means the brain answers.
 */
object FastPath {
    private const val TAG = "Assistant"
    @Volatile private var model: GenerativeModel? = null
    @Volatile var ready = false; private set

    data class Intent(val action: String, val app: String = "", val level: Int = 0)

    private val HOME_WORDS = Regex("\\b(tv|television|telly|volume|louder|quieter|softer|mute|unmute|sound|pause|unpause|resume|play|continue|rewind|fast ?forward|skip|go back|go forward|jump back|jump ahead|back up|hold it|netflix|prime|disney|youtube|hulu|max|hbo|apple tv|peacock|spotify|car mode)\\b", RegexOption.IGNORE_CASE)
    fun mightBeHome(text: String) = HOME_WORDS.containsMatchIn(text)

    private val APPS = listOf("netflix", "prime video", "disney+", "youtube", "hulu", "max", "apple tv", "peacock", "spotify")

    /** At start-up: make sure the on-device model is there (it may need a one-time download), then warm it. */
    fun start(c: Context) {
        Thread({
            runCatching {
                val m = Generation.getClient()
                model = m
                runBlocking {
                    var st = m.checkStatus()
                    Log.i(TAG, "fast path: on-device model status $st")
                    if (st == FeatureStatus.DOWNLOADABLE || st == FeatureStatus.DOWNLOADING) {
                        m.download().collect { }
                        st = m.checkStatus()
                    }
                    if (st == FeatureStatus.AVAILABLE) {
                        m.warmup()
                        ready = true
                        Log.i(TAG, "fast path: ready (${m.getBaseModelName()})")
                    } else Log.w(TAG, "fast path: no on-device model here ($st): plain home commands use the strict pattern, everything else goes to the brain")
                }
            }.onFailure { Log.w(TAG, "fast path: ${it.message}") }
            Clips.prepare(c)
        }, "assistant-fastpath").start()
    }

    /** What the user meant, if it's a home command; null = let the brain answer. */
    private val HUSH = Regex("^(?:ok |okay )?(?:stop|stop it|stop talking|cancel|never ?mind|forget it|be quiet|quiet|shush|that's all|that's enough|enough)(?: please| " + Regex.escape(Persona.NAME.lowercase()) + "| thanks?)?$", RegexOption.IGNORE_CASE)

    fun understand(text: String): Intent? {
        // "Stop" on its own (e.g. right after interrupting the user): go quiet, no reply.
        if (HUSH.matches(text.trim().replace(Regex("[^A-Za-z' ]"), "").replace(Regex("\\s+"), " ").trim())) return Intent("hush")
        if (!mightBeHome(text)) return null
        // "Stop, open Hulu" / "no, put on Netflix": the lead-in only says the old answer is over.
        val t = text.trim().replace(Regex("^(?:no|nope|stop|wait|hold on|actually|cancel that)\\b[,.!]?\\s+(?:please\\s+)?", RegexOption.IGNORE_CASE), "")
        if (t != text.trim()) return understand(t)
        val m = model
        if (!ready || m == null) return strict(text)
        return byModel(m, text)
    }

    /**
     * Without an on-device model (this phone has none: status UNAVAILABLE), only plain, whole commands
     * are handled on the phone. Anything with more in it ("turn the TV off after this episode") goes
     * to the brain, so a near-miss costs a little time, never a wrong action.
     */
    fun strict(text: String): Intent? {
        val t = text.lowercase().replace(Regex("[^a-z0-9+' ]"), " ")
            .replace(Regex("\\b(hey |ok )?" + Regex.escape(Persona.NAME.lowercase()) + "\\b|\\b" + Regex.escape(Persona.WAKE_PHRASE.lowercase()) + "\\b|\\bplease\\b|\\bcan you\\b|\\bcould you\\b|\\bfor me\\b|\\bnow\\b|\\bthanks?\\b"), " ")
            .replace(Regex("\\btelevision\\b|\\btelly\\b"), "tv").replace(Regex("\\s+"), " ").trim()
        val tv = "(?:the )?tv"
        fun m(re: String) = Regex("^$re$").matchEntire(t)
        m("(?:turn|switch|power) (on|off) $tv|(?:turn|switch|power) $tv (on|off)|$tv (on|off)")?.let { r ->
            return Intent(if ((r.groupValues.drop(1).firstOrNull { it.isNotEmpty() }) == "on") "tv_on" else "tv_off") }
        m("(?:open|put on|launch|start|play|turn on|go to|switch to) (.+?)(?: on $tv)?")?.let { r ->
            val a = r.groupValues[1].replace("disney plus", "disney+").replace("disney", "disney+").replace("++", "+").replace("amazon prime", "prime video")
                .replace(Regex("^prime$"), "prime video").replace("hbo max", "max").replace("hbo", "max")
            APPS.firstOrNull { it == a }?.let { return Intent("open", it) }
        }
        m("(?:set |turn |put )?(?:$tv )?(?:the )?volume (?:to|at|on) (\\d{1,3})|(?:set )?$tv (?:volume )?to (\\d{1,3})|volume (\\d{1,3})")?.let { r ->
            r.groupValues.drop(1).firstOrNull { it.isNotEmpty() }?.toIntOrNull()?.takeIf { it <= 100 }?.let { return Intent("volume_set", level = it) } }
        m("(?:turn |crank )?(?:$tv |the volume |it |the sound )?(up|down)(?: (?:the )?(?:tv|volume))?(?: by (\\d{1,2}))?|(?:$tv )?volume (up|down)(?: by (\\d{1,2}))?|(louder|quieter|softer)(?: please)?|make (?:it|$tv) (louder|quieter|softer)")?.let { r ->
            val g = r.groupValues.drop(1)
            val dir = g.firstOrNull { it in setOf("up", "down", "louder", "quieter", "softer") } ?: return null
            val by = g.firstOrNull { it.toIntOrNull() != null }?.toInt() ?: 5
            return Intent(if (dir == "up" || dir == "louder") "volume_up" else "volume_down", level = by)
        }
        m("mute(?: $tv)?|$tv mute")?.let { return Intent("mute") }
        m("pause(?: (?:it|the show|the movie|this|$tv))?|$tv pause|hold it")?.let { return Intent("pause") }
        m("(?:un ?pause|resume|play|continue|keep playing)(?: (?:it|the show|the movie|this|$tv))?|$tv play")?.let { return Intent("play") }
        m("(?:go back|rewind|skip back|jump back|back up)(?: (?:it|the show|the movie|this|$tv))?(?: (?:by )?(\\d{1,3}) ?(seconds?|secs?|minutes?|mins?))?")?.let { r ->
            return Intent("skip_back", level = seconds(r.groupValues[1], r.groupValues[2])) }
        m("(?:go forward|fast ?forward|skip ahead|skip forward|jump ahead|jump forward|skip)(?: (?:it|the show|the movie|this|$tv))?(?: (?:by )?(\\d{1,3}) ?(seconds?|secs?|minutes?|mins?))?")?.let { r ->
            return Intent("skip_forward", level = seconds(r.groupValues[1], r.groupValues[2])) }
        m("unmute(?: $tv)?|$tv unmute|(?:turn )?(?:the )?sound (?:back )?on")?.let { return Intent("unmute") }
        m("(?:turn )?car mode (on|off)|(?:turn )?(on|off) car mode")?.let { r ->
            return Intent(if (r.groupValues.drop(1).firstOrNull { it.isNotEmpty() } == "on") "car_on" else "car_off") }
        return null
    }

    /** "30 seconds" -> 30, "2 minutes" -> 120, nothing -> 10 (one press). */
    private fun seconds(n: String, unit: String): Int {
        val v = n.toIntOrNull() ?: return 10
        return if (unit.startsWith("min")) v * 60 else v
    }

    private fun byModel(m: GenerativeModel, text: String): Intent? {
        val t0 = System.currentTimeMillis()
        val prompt = """You turn a spoken request into one JSON object. Reply with JSON only.
Actions: "tv_on", "tv_off", "open" (needs "app"), "volume_set" (needs "level" 0-100), "volume_up", "volume_down" ("level" = how much, default 5), "mute", "unmute", "car_on", "car_off", "none".
Apps: ${APPS.joinToString(", ")}.
Use "none" for anything that is not one of those exact actions (questions, anything about shows, schedules, other devices, or if unsure).
Examples:
"turn the TV off" -> {"action":"tv_off"}
"put on Netflix" -> {"action":"open","app":"netflix"}
"TV volume to 25" -> {"action":"volume_set","level":25}
"turn it up a bit" -> {"action":"volume_up","level":5}
"what's on Netflix tonight" -> {"action":"none"}
Request: "${text.replace("\"", "'")}""""
        val out = runCatching { runBlocking { withTimeoutOrNull(2500) { m.generateContent(prompt) } } }.getOrNull()
        val raw = out?.candidates?.firstOrNull()?.text.orEmpty()
        val j = runCatching { JSONObject(raw.substring(raw.indexOf('{'), raw.lastIndexOf('}') + 1)) }.getOrNull()
        val action = j?.optString("action").orEmpty()
        Log.i(TAG, "fast path: ${said(text)} -> ${j ?: "no answer"} in ${System.currentTimeMillis() - t0} ms")
        val valid = setOf("tv_on", "tv_off", "open", "volume_set", "volume_up", "volume_down", "mute", "unmute", "car_on", "car_off")
        if (action !in valid) return null
        val app = j!!.optString("app").lowercase().let { a -> APPS.firstOrNull { it == a || it.startsWith(a) || a.startsWith(it) } }.orEmpty()
        if (action == "open" && app.isEmpty()) return null
        val level = j.optInt("level", if (action.startsWith("volume_") && action != "volume_set") 5 else 0).coerceIn(0, 100)
        if (action == "volume_set" && !j.has("level")) return null
        return Intent(action, app, level)
    }

    /** The spoken line for an intent (pre-recorded, so it plays instantly). */
    fun line(i: Intent): String = Persona.say(when (i.action) {
        "tv_on" -> "Turning the TV on%A."
        "tv_off" -> "Turning the TV off%A."
        "open" -> "Opening ${Clips.appName(i.app)}%A."
        "volume_set" -> "Setting the volume%A."
        "volume_up" -> "Turning it up%A."
        "volume_down" -> "Turning it down%A."
        "mute" -> "Muting the TV%A."
        "unmute" -> "Sound back on%A."
        "car_on" -> "Car mode on%A."
        "car_off" -> "Car mode off%A."
        "pause" -> "Paused%A."
        "play" -> "Playing%A."
        "skip_back" -> "Going back%A."
        "skip_forward" -> "Skipping ahead%A."
        else -> ""
    })

    /** Pre-recorded the assistant lines, voiced once by the brain's voice and kept on the phone. */
    object Clips {
        val OFFLINE = Persona.say("I can't reach my brain just now%A. I can still run the TV and car mode.")
        fun appName(a: String) = when (a) { "disney+" -> "Disney Plus"; "max" -> "HBO Max"; "apple tv" -> "Apple TV"; "prime video" -> "Prime Video"; else -> a.replaceFirstChar { it.uppercase() } }
        private fun all() = listOf("tv_on", "tv_off", "volume_set", "volume_up", "volume_down", "mute", "unmute", "car_on", "car_off", "pause", "play", "skip_back", "skip_forward")
            .map { line(Intent(it)) } + APPS.map { line(Intent("open", it)) } + OFFLINE
        private fun file(c: Context, text: String) = File(c.filesDir, "clips/${text.hashCode().toUInt().toString(16)}.mp3")

        fun get(c: Context, text: String): String? = file(c, text).takeIf { it.exists() }?.readBytes()?.let { android.util.Base64.encodeToString(it, android.util.Base64.NO_WRAP) }

        fun prepare(c: Context) {
            val key = Prefs.passphrase(c) ?: return
            var made = 0
            for (t in all()) {
                val f = file(c, t)
                if (f.exists()) continue
                runCatching {
                    val conn = URL("${BuildConfig.API_BASE}/api/speak").openConnection() as HttpURLConnection
                    conn.requestMethod = "POST"; conn.connectTimeout = 10_000; conn.readTimeout = 20_000; conn.doOutput = true
                    conn.setRequestProperty("content-type", "application/json"); conn.setRequestProperty("x-assistant-key", key)
                    conn.outputStream.use { it.write(JSONObject().put("text", t).toString().toByteArray()) }
                    val a = JSONObject(conn.inputStream.bufferedReader().use { it.readText() }).optString("audio")
                    if (a.isNotBlank() && a != "null") { f.parentFile?.mkdirs(); f.writeBytes(android.util.Base64.decode(a, android.util.Base64.DEFAULT)); made++ }
                }.onFailure { Log.w(TAG, "clip \"$t\": ${it.message}") }
            }
            Log.i(TAG, "fast path: ${all().count { file(c, it).exists() }}/${all().size} spoken lines on the phone ($made new)")
        }
    }
}
