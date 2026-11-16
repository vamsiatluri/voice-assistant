package org.voiceassistant

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.media.ToneGenerator
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Base64
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/**
 * Always-on assistant: listens for the wake phrase on-device, then hears the request,
 * asks the brain in AWS, speaks the reply, and listens once more for a follow-up.
 */
class AssistantService : Service() {
    companion object {
        const val TAG = "Assistant"
        const val ACTION_STOP = "org.voiceassistant.STOP"
        const val ACTION_TALK = "org.voiceassistant.TALK"          // tap-to-talk from the app
        const val ACTION_ASK = "org.voiceassistant.ASK"            // typed question from the app: --es text
        const val DEBUG_ASK = "org.voiceassistant.DEBUG_ASK"       // adb: --es text "..."
        const val DEBUG_SCORE = "org.voiceassistant.DEBUG_SCORE"   // adb: --es file <pcm in filesDir>
        const val THRESHOLD = 0.5f
        const val NEAR_MISS = 0.25f       // logged, so a missed wake phrase shows up in the log
        const val CAR_VOICE_RELAX = 0.12f // voiceprint allowance while the car's Bluetooth is connected
        const val MIN_VOICE = 0.25f
        /** Voice match needed to cut the assistant off: loose, because the echo-cancelled mic distorts the user's voice. */
        const val BARGE_VOICE = 0.22f
        /** the wake phrase while the assistant talks: the assistant never says it, so a strong wake score alone is enough (the user's read 0.84 on 11-13). */
        const val BARGE_WAKE = 0.7f
        /** Talking over the assistant (call audio leaves its echo at ~0 dB): this loud for this many 80 ms chunks, loosely the user's voice. */
        const val TALK_DB = 38.0
        const val TALK_CHUNKS = 3
        const val TALK_VOICE = 0.15f
        // LOW importance: status updates must never wake the screen or pop a card over the orb.
        private const val CHANNEL = "assistant_quiet"
        private const val NOTIF_ID = 1

        @Volatile var state: String = "Off"; private set
        var onState: ((String) -> Unit)? = null
        var running = false; private set
        /** Last thing the assistant said, for the app screen, notification and widget. */
        @Volatile var lastReply: String? = null; private set
        @Volatile private var instance: AssistantService? = null

        /** A text from a contact arrived (from MessageListener). Read it when the assistant is free. */
        fun announceText(ctx: Context, key: String, from: String, body: String) {
            val svc = instance ?: return
            svc.main.post { svc.enqueue(Announcement.Text(key, from, body)) }
        }
    }

    // ---------- announcements: Claude threads waiting on the user, texts from contacts ----------
    sealed class Announcement {
        data class Claude(val id: String, val said: String, val audio: String) : Announcement()
        data class Text(val key: String, val from: String, val body: String) : Announcement()
    }
    private val queue = ArrayDeque<Announcement>()
    /** What the user's next words might be answering, kept for a few minutes so "yes, send it" still lands. */
    private var bridgeId: String? = null
    private var textKey: String? = null
    private var textFrom: String? = null
    private var contextAt = 0L
    /** An announcement just played: if the user says nothing in the first window, listen once more. */
    private var announceRetry = false
    private val contextTtl = 5 * 60_000L

    private fun quietHours(): Boolean { val h = java.time.LocalTime.now().hour; return h >= 23 || h < 7 }
    private fun inCall() = audio.mode == AudioManager.MODE_IN_CALL || audio.mode == AudioManager.MODE_IN_COMMUNICATION

    fun enqueue(a: Announcement) {
        if (a is Announcement.Text && quietHours() && !CarMode.inCar(this)) { MessageListener.markMissed(a.key); Log.i(TAG, "text held for quiet hours"); return }
        if (SleepMode.asleep(this)) { if (a is Announcement.Text) MessageListener.markMissed(a.key); Log.i(TAG, "${if (a is Announcement.Text) "text" else "announcement"} held: sleep mode"); return }
        queue.addLast(a)
        drain()
    }

    private fun idle() = running && listening && player == null && state.startsWith("Listening for")

    private fun drain() {
        if (queue.isEmpty() || !idle() || inCall()) return
        val a = queue.removeFirst()
        stopWakeLoop()
        contextAt = System.currentTimeMillis()
        when (a) {
            is Announcement.Claude -> {
                bridgeId = a.id
                lastReply = a.said
                announceRetry = true
                chime()
                main.postDelayed({ speak(a.audio) }, 350)
            }
            is Announcement.Text -> {
                textKey = a.key; textFrom = a.from
                announceRetry = true
                chime()
                ask("", JSONObject().put("incomingText", JSONObject().put("from", a.from).put("text", a.body)))
            }
        }
    }

    private val bridgePoll = object : Runnable {
        override fun run() {
            if (!running) return
            if (idle() && !inCall() && !SleepMode.asleep(this@AssistantService)) thread(name = "assistant-bridge") {
                runCatching {
                    val j = getJson("/api/bridge/next")
                    val e = j.optJSONObject("event") ?: return@runCatching
                    val audioB64 = j.optString("audio").takeIf { it.isNotBlank() && it != "null" } ?: return@runCatching
                    main.post { enqueue(Announcement.Claude(e.getString("id"), e.optString("announcement"), audioB64)) }
                }.onFailure { Log.w(TAG, "bridge poll: ${it.message}") }
            }
            main.postDelayed(this, 15_000)
        }
    }

    private fun getJson(path: String): JSONObject {
        val key = Prefs.passphrase(this) ?: throw IllegalStateException("no access phrase set")
        val c = URL("${BuildConfig.API_BASE}$path").openConnection() as HttpURLConnection
        c.connectTimeout = 10_000; c.readTimeout = 30_000
        c.setRequestProperty("x-assistant-key", key)
        if (c.responseCode !in 200..299) throw IllegalStateException("HTTP ${c.responseCode}")
        return JSONObject(c.inputStream.bufferedReader().use { it.readText() })
    }

    /** A typed question gets a spoken answer but no voice follow-up: you chose to type for a reason. */
    private var typedTurn = false

    private val main = Handler(Looper.getMainLooper())
    private lateinit var detector: WakeDetector
    /** Voiceprint check on the wake phrase. Built on the audio thread (29 MB model), null if it fails to load. */
    @Volatile private var speaker: SpeakerId? = null
    @Volatile private var enrolling = false
    private val enrolSamples = mutableListOf<FloatArray>()
    @Volatile private var listening = false
    private var audioThread: Thread? = null
    private var recognizer: SpeechRecognizer? = null
    private var player: MediaPlayer? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val audio by lazy { getSystemService(AudioManager::class.java) }
    private var focus: AudioFocusRequest? = null

    // ---------- lifecycle ----------
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        running = true
        instance = this
        SleepMode.catchUp(this)
        detector = WakeDetector(this, listOf("wakeword.onnx"))
        createChannel()
        startForeground(NOTIF_ID, notification("Listening for \"${Persona.WAKE_PHRASE}\""), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "assistant:listen").apply { setReferenceCounted(false); acquire() }
        // adb test hooks exist only in debug builds; the installed release build has no external entry points
        if (BuildConfig.DEBUG) registerReceiver(debugReceiver, IntentFilter().apply { addAction(DEBUG_ASK); addAction(DEBUG_SCORE) }, RECEIVER_EXPORTED)
        registerReceiver(carReceiver, CarMode.filter, RECEIVER_EXPORTED)
        thread(name = "assistant-tv") { SamsungTv.prime(this) }
        registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, i: Intent) {
                // A short neutral line (never the "can't reach my brain" one: the user heard that and thought the assistant was broken).
                val clip = FastPath.Clips.get(this@AssistantService, Persona.say("Turning it up%A.")) ?: return
                main.post {
                    callStyle = i.getStringExtra("mode") != "media"
                    Log.i(TAG, "echo test: speaking with ${if (callStyle) "call" else "media"} audio")
                    stopWakeLoop(); typedTurn = i.getStringExtra("followup") != "1"; replyStreaming = false; speak(clip)
                }
            }
        }, IntentFilter("org.voiceassistant.ECHO_TEST"), android.Manifest.permission.DUMP, null, RECEIVER_EXPORTED)
        registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, i: Intent) {
                val f = java.io.File(getExternalFilesDir(null), i.getStringExtra("file") ?: "test.pcm")
                if (!f.exists()) { Log.w(TAG, "recog test: no ${f.name}"); return }
                val pipe = android.os.ParcelFileDescriptor.createPipe()
                thread(name = "assistant-recog-test") {
                    android.os.ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]).use { o ->
                        val b = f.readBytes(); var k = 0
                        while (k < b.size) { val n = minOf(2560, b.size - k); o.write(b, k, n); k += n; Thread.sleep(80) }   // real time
                        Thread.sleep(1500)
                    }
                }
                main.post { pipedOnDevice = i.getStringExtra("rec") == "ondevice"; pipedRecognizer?.destroy(); pipedRecognizer = null
                    Log.i(TAG, "recog test: ${f.name}, ${f.length()} bytes, ${if (pipedOnDevice) "on-device" else "default"} recogniser"); stopWakeLoop(); typedTurn = false; listenForRequest(followUp = false, audioIn = pipe[0]) }
            }
        }, IntentFilter("org.voiceassistant.RECOG_TEST"), android.Manifest.permission.DUMP, null, RECEIVER_EXPORTED)
        FastPath.start(this)
        startWakeLoop()
        main.postDelayed(bridgePoll, 5_000)
    }

    /** The car's Bluetooth came or went: tell the brain (threads are announced while the user drives) and say so. */
    private val carReceiver = CarMode.receiver { on ->
        // The car's Bluetooth changes the phone's audio route; a recorder opened before it can keep
        // reading silence. Re-open the wake mic on the new route once it has settled.
        main.postDelayed({ if (listening && !bargeIn) { Log.i(TAG, "car ${if (on) "connected" else "gone"}: re-opening the wake mic"); stopWakeLoop(); startWakeLoop() } }, 1500)
        thread(name = "assistant-car") {
            runCatching { postJson("/api/away", JSONObject().put("on", on).put("by", "car")) }.onFailure { Log.w(TAG, "car away: ${it.message}") }
            if (on && !SleepMode.asleep(this)) {
                val audio = runCatching { speakOnly(Persona.say("Car mode%A. I'll read your texts and threads as they come in.")) }.getOrNull()
                if (audio != null) main.post { if (idle() && !inCall()) { stopWakeLoop(); typedTurn = true; speak(audio) } }
            }
        }
    }

    private fun postJson(path: String, body: JSONObject): JSONObject {
        val key = Prefs.passphrase(this) ?: throw IllegalStateException("no access phrase set")
        val c = URL("${BuildConfig.API_BASE}$path").openConnection() as HttpURLConnection
        c.requestMethod = "POST"; c.connectTimeout = 10_000; c.readTimeout = 20_000; c.doOutput = true
        c.setRequestProperty("content-type", "application/json"); c.setRequestProperty("x-assistant-key", key)
        c.outputStream.use { it.write(body.toString().toByteArray()) }
        if (c.responseCode !in 200..299) throw IllegalStateException("HTTP ${c.responseCode}")
        return JSONObject(c.inputStream.bufferedReader().use { it.readText() })
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { stopSelf(); return START_NOT_STICKY }
            ACTION_TALK -> main.post { onWake(fromTap = true) }
            ACTION_ASK -> intent.getStringExtra("text")?.trim()?.takeIf { it.isNotEmpty() }?.let { t ->
                main.post {
                    if (state == "Thinking") return@post
                    player?.release(); player = null
                    recognizer?.cancel()
                    stopWakeLoop()
                    typedTurn = true
                    ask(t)
                }
            }
            AssistantWidget.ACTION_REFRESH -> AssistantWidget.refresh(this, state, lastReply)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        stopEarbudMic()
        releaseFocus()
        instance = null
        main.removeCallbacks(bridgePoll)
        restoreVolume()
        stopWakeLoop()
        recognizer?.destroy(); recognizer = null
        player?.release(); player = null
        runCatching { unregisterReceiver(debugReceiver) }
        runCatching { unregisterReceiver(carReceiver) }
        wakeLock?.release()
        detector.close()
        setState("Off")
        super.onDestroy()
    }

    // ---------- state + notification ----------
    private fun setState(s: String) {
        state = s
        Log.i(TAG, "state: $s")
        main.post { onState?.invoke(s) }
        if (running) getSystemService(NotificationManager::class.java).notify(NOTIF_ID, notification(s))
        AssistantWidget.refresh(this, s, lastReply)
        OrbWidget.refresh(this, s)
    }

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "${Persona.NAME} status", NotificationManager.IMPORTANCE_LOW).apply {
                setShowBadge(false); setSound(null, null); enableVibration(false)
                lockscreenVisibility = Notification.VISIBILITY_SECRET
            })
    }

    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val talk = PendingIntent.getService(this, 2, Intent(this, AssistantService::class.java).setAction(ACTION_TALK), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, AssistantService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        val b = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_orb)
            .setContentTitle(Persona.NAME)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(lastReply?.let { "$text\n$it" } ?: text))
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setVisibility(Notification.VISIBILITY_SECRET)   // lock screen shows the orb only; the card stays in the shade
            .setCategory(Notification.CATEGORY_STATUS)
            .addAction(Notification.Action.Builder(null, "Talk", talk).build())
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
        // No Live Update promotion: One UI showed it as a card anyway, and a promoted
        // notification re-surfaces on every update, covering the lock-screen orb.
        return b.build()
    }


    // ---------- 1. wake word ----------
    /** True while the wake loop is running UNDER the assistant's own speech, so the user can interrupt it. */
    @Volatile private var bargeIn = false

    /** barge = listen for the wake phrase while the assistant is talking (echo-cancelled mic, the user's voice only). */
    private fun startWakeLoop(barge: Boolean = false) {
        if (listening) return
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) { setState("Microphone permission needed"); return }
        if (barge && speaker?.enrolled != true) return   // without the user's voiceprint the assistant could wake on its own voice
        listening = true
        bargeIn = barge
        resetBarge(); talkRun = 0
        detector.clearHistory()
        if (!barge) setState(if (detector.ready) "Listening for \"${Persona.WAKE_PHRASE}\"" else "No wake word trained yet: tap the orb to talk")
        audioThread = thread(name = if (barge) "assistant-barge" else "assistant-wake") {
            val minBuf = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            // VOICE_COMMUNICATION turns on the phone's echo canceller, so the assistant's own voice from the speaker is mostly removed.
            val rec = AudioRecord(if (barge) MediaRecorder.AudioSource.VOICE_COMMUNICATION else MediaRecorder.AudioSource.VOICE_RECOGNITION, 16000, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, WakeDetector.CHUNK * 8))
            val chunk = ShortArray(WakeDetector.CHUNK)
            val ring = ShortArray(16000 * 2)   // last 2 s of audio: the wake phrase that fired the wake
            var ringFill = 0
            var silentChunks = 0          // 80 ms each: a recorder handed silence (another app has the mic, or a stale route)
            var lastNearMiss = 0L
            if (speaker == null) speaker = runCatching { SpeakerId(this) }.onFailure { Log.e(TAG, "speaker model failed to load", it) }.getOrNull()
            try {
                rec.startRecording()
                while (listening) {
                    var got = 0
                    while (got < chunk.size && listening) {
                        val n = rec.read(chunk, got, chunk.size - got)
                        if (n < 0) throw IllegalStateException("AudioRecord read $n")
                        got += n
                    }
                    if (!listening) break
                    System.arraycopy(ring, chunk.size, ring, 0, ring.size - chunk.size)
                    chunk.copyInto(ring, ring.size - chunk.size)
                    ringFill = minOf(ring.size, ringFill + chunk.size)
                    val score = detector.push(chunk)
                    if (!bargeIn) {
                        var peak = 0; for (v in chunk) { val a = kotlin.math.abs(v.toInt()); if (a > peak) peak = a }
                        silentChunks = if (peak == 0) silentChunks + 1 else 0   // exact digital zeros: a real quiet room is never this quiet
                        if (silentChunks >= 250) throw IllegalStateException("mic is silent for 20 s${if (CarMode.inCar(this)) " (in the car)" else ""}: re-opening it")
                        val now = System.currentTimeMillis()
                        if (score in NEAR_MISS..(THRESHOLD - 0.0001f) && now - lastNearMiss > 3000) {
                            lastNearMiss = now; Log.i(TAG, "wake near miss: score=${"%.2f".format(score)}${if (CarMode.inCar(this)) " (in the car)" else ""}")
                        }
                    }
                    if (bargeIn) {
                        var sq = 0.0; for (v in chunk) sq += v.toDouble() * v
                        val db = 10 * kotlin.math.log10(sq / chunk.size + 1.0)
                        echoLevels.add(db)
                        // With call audio the assistant's own voice is cancelled to ~0 dB, so sustained sound here is the user
                        // talking over it ("stop", "wait, that's not what I meant", "no, open Hulu").
                        talkRun = if (db >= TALK_DB) talkRun + 1 else 0
                        if (inCallAudio && talkRun >= TALK_CHUNKS && !talkOverMuted) {
                            val spk = speaker
                            val voice = if (spk != null && spk.enrolled) spk.score(ring.copyOfRange(ring.size - minOf(ringFill, 16000), ring.size)) else 1f
                            Log.i(TAG, "talk-over check: ${"%.0f".format(db)} dB, voice ${"%.2f".format(voice)}")
                            if (voice >= TALK_VOICE) {
                                // Stop the assistant now, keep recording what the user says (from 1.5 s back) until the user pauses, then
                                // have the brain transcribe it. The phone's recognisers return "" for audio handed to them.
                                main.post { onTalkOverStart() }
                                val said = java.io.ByteArrayOutputStream()
                                said.write(shortsToBytes(ring.copyOfRange(ring.size - minOf(ringFill, 24000), ring.size)))
                                var quiet = 0; val capEnd = System.currentTimeMillis() + 5_000
                                while (System.currentTimeMillis() < capEnd && quiet < 10) {
                                    var g = 0
                                    while (g < chunk.size) { val n = rec.read(chunk, g, chunk.size - g); if (n < 0) break; g += n }
                                    said.write(shortsToBytes(chunk))
                                    var q = 0.0; for (v in chunk) q += v.toDouble() * v
                                    quiet = if (10 * kotlin.math.log10(q / chunk.size + 1.0) < TALK_DB - 6) quiet + 1 else 0
                                }
                                runCatching { java.io.File(getExternalFilesDir(null), "last-talkover.pcm").writeBytes(said.toByteArray()) }
                                listening = false
                                val pcm = said.toByteArray()
                                thread(name = "assistant-transcribe") { onTalkOverAudio(pcm) }
                                break
                            }
                            talkRun = 0
                        }
                    }
                    if (score >= THRESHOLD) {
                        Log.i(TAG, "wake word detected, score=$score")
                        val phrase = ring.copyOfRange(ring.size - minOf(ringFill, 24000), ring.size)   // ~1.5 s
                        val spk = speaker
                        if (enrolling && spk != null) {
                            val e = spk.embed(phrase)
                            detector.clearHistory()
                            main.post { onEnrolSample(e) }
                            continue
                        }
                        if (bargeIn && score < BARGE_WAKE) { detector.clearHistory(); continue }   // weak wake under the user's own speech: ignore
                        if (spk != null && spk.enrolled && !bargeIn) {
                            val voice = spk.score(phrase)
                            // A clearly heard wake word may come with a weaker voice match when the TV or a room is loud
                            // (11-13: 0.45-0.55 against 0.55 with the TV on). Under the assistant's own speech, never relax.
                            // In the car road noise and the cabin change how the owner sounds, and nobody else
                            // is likely to say the wake phrase there.
                            val car = if (CarMode.inCar(this)) CAR_VOICE_RELAX else 0f
                            val need = when {
                                bargeIn -> BARGE_VOICE        // echo-cancelled mic: the user's own wake phrase read 0.31 on 11-13
                                score >= 0.9f -> spk.threshold - 0.10f - car
                                else -> spk.threshold - car
                            }.coerceAtLeast(MIN_VOICE)
                            if (voice < need) {
                                Log.i(TAG, "wake ignored: not the owner's voice (voice=${"%.2f".format(voice)} < ${"%.2f".format(need)}, score=${"%.2f".format(score)}${if (car > 0) ", in the car" else ""})")
                                detector.clearHistory()
                                continue
                            }
                            Log.i(TAG, "voice match ${"%.2f".format(voice)}${if (bargeIn) " (interrupting)" else ""}")
                        }
                        listening = false
                        val wasBarge = bargeIn
                        main.post { if (wasBarge) onBargeIn() else onWake(fromTap = false) }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "wake loop failed", e)
                main.postDelayed({ if (running && !listening) startWakeLoop() }, 2000)
            } finally {
                runCatching { rec.stop() }; rec.release()
            }
        }
    }

    // ---------- voice enrolment: the user says the wake phrase five times ----------
    private fun startEnrolment() {
        if (speaker == null) { Log.e(TAG, "enrolment: speaker model not loaded"); return }
        enrolSamples.clear()
        enrolling = true
        Log.i(TAG, "voice enrolment started")
    }

    private fun onEnrolSample(e: FloatArray) {
        if (!enrolling) return
        enrolSamples += e
        Log.i(TAG, "enrolment sample ${enrolSamples.size}/${SpeakerId.ENROL_SAMPLES}")
        chime()
        if (enrolSamples.size < SpeakerId.ENROL_SAMPLES) return
        enrolling = false
        val spk = speaker ?: return
        val self = enrolSamples.map { s -> enrolSamples.filter { it !== s }.map { o -> s.indices.sumOf { (s[it] * o[it]).toDouble() } }.average() }
        val fit = spk.enrol(enrolSamples.toList())
        // Accept anything comfortably inside the user's own spread: the user's weakest sample-to-sample match, minus margin.
        val threshold = (self.min() - 0.15).coerceIn(0.30, 0.55).toFloat()
        Prefs.setVoiceThreshold(this, threshold)
        Log.i(TAG, "voiceprint saved: fit=${"%.2f".format(fit)} pairwise=${self.joinToString { "%.2f".format(it) }} threshold=${"%.2f".format(threshold)}")
        sayLine(Persona.say("Got it%A. From now on I'll only wake for your voice."))
    }

    /** Speak a fixed line from the phone itself (no conversation turn). */
    private fun sayLine(text: String) {
        lastReply = text
        thread(name = "assistant-say") {
            val audioB64 = runCatching { speakOnly(text) }.getOrNull()
            main.post {
                if (audioB64 == null || !idle()) return@post
                stopWakeLoop(); typedTurn = true   // no follow-up listen after a notice
                speak(audioB64)
            }
        }
    }

    private fun stopWakeLoop() {
        listening = false
        audioThread?.join(1500); audioThread = null
        if (bargeIn) logEchoLevels("reply")
        bargeIn = false
    }

    // ---------- talking over the assistant ----------
    // While the assistant speaks, the echo-cancelled mic still carries some of the assistant's own voice. The first ~0.5 s of each
    // reply sets that baseline; a sustained rise well above it (~0.3 s, 10 dB) that loosely matches the user's voice means
    // the user is talking over it ("stop", "no, that's not what I meant", "stop, open Hulu"). No wake word needed.
    private val bargeLevels = ArrayList<Double>()
    private var bargeBaseline = 0.0
    private var bargeRun = 0
    private var bargeCooldown = 0
    private fun resetBarge() { bargeLevels.clear(); bargeBaseline = 0.0; bargeRun = 0; bargeCooldown = 0 }

    private fun bargeHeard(chunk: ShortArray, ring: ShortArray, ringFill: Int): Boolean {
        var sum = 0.0
        for (v in chunk) sum += v.toDouble() * v
        val db = 10 * kotlin.math.log10(sum / chunk.size + 1.0)
        if (bargeLevels.size < 6) { bargeLevels.add(db); if (bargeLevels.size == 6) bargeBaseline = bargeLevels.sorted()[3]; return false }
        if (bargeCooldown > 0) { bargeCooldown--; return false }
        if (db > maxOf(bargeBaseline + 10.0, 45.0)) bargeRun++ else { bargeRun = 0; bargeBaseline = 0.9 * bargeBaseline + 0.1 * db }
        if (bargeRun < 4) return false
        bargeRun = 0
        val spk = speaker
        val voice = if (spk != null && spk.enrolled) spk.score(ring.copyOfRange(ring.size - minOf(ringFill, 16000), ring.size)) else 1f
        Log.i(TAG, "barge check: level ${"%.0f".format(db)} dB over baseline ${"%.0f".format(bargeBaseline)}, voice ${"%.2f".format(voice)}")
        if (voice >= BARGE_VOICE) return true
        bargeCooldown = 6   // not the user (TV, someone else): don't re-check every chunk
        return false
    }

    /** The user talked over the assistant: stop mid-sentence and listen to the user. */
    @Volatile private var interruptedLast = false
    @Volatile private var feeding = false
    private var talkRun = 0
    private fun shortsToBytes(a: ShortArray): ByteArray {
        val b = ByteArray(a.size * 2)
        for (i in a.indices) { b[i * 2] = (a[i].toInt() and 0xff).toByte(); b[i * 2 + 1] = (a[i].toInt() shr 8).toByte() }
        return b
    }

    /** What the assistant is saying this turn (sent with talk-over audio so the brain can judge it). */
    private val speaking = StringBuilder()
    /** Paused because someone talked: waiting to hear whether it was said to the assistant. */
    @Volatile private var talkPaused = false
    /** Room conversation already paused this reply once: don't keep stuttering. the wake phrase still interrupts. */
    @Volatile private var talkOverMuted = false
    /** The user said "stop listening" (go_quiet): this reply ends the conversation, back to the wake word only. */
    @Volatile private var quietAfterReply = false

    /** Someone talked over the assistant: pause at once (the mic keeps recording). Whether it stops or carries on
     *  depends on whether the words were said to the assistant (11-13: it stopped for "You put it to five, I didn't
     *  put it to five", family talking in the room). */
    private fun onTalkOverStart() {
        Log.i(TAG, "talked over: pausing")
        talkPaused = true
        player?.runCatching { if (isPlaying) pause() }
        setState("Listening")
    }

    /** It was said to the assistant: drop the rest of the reply. */
    private fun stopForTalkOver() {
        talkPaused = false
        interruptedLast = true
        clips.clear(); replyStreaming = false
        player?.runCatching { stop() }; player?.release(); player = null   // music stays paused: the user is about to talk
        leaveCallAudio()
        playedThisTurn = false
    }

    /** It wasn't (or nothing was said): carry on from where the assistant paused, and ignore room talk for the rest of this reply. */
    private fun resumeAfterTalkOver() {
        talkPaused = false
        talkOverMuted = true
        val p = player
        when {
            p != null -> { runCatching { p.start() }; setState("Speaking"); startWakeLoop(barge = true) }
            clips.isNotEmpty() -> playNext()
            replyStreaming -> { setState("Thinking"); startWakeLoop(barge = true) }   // next sentence still on its way
            else -> doneSpeaking(followUpWanted = false)
        }
    }

    /** What the user said over the assistant, as text, then handled like any request. Falls back to a fresh listen. */
    private fun onTalkOverAudio(pcm: ByteArray) {
        val t0 = System.currentTimeMillis()
        val said = speaking.toString().trim()
        val res = runCatching {
            postJson("/api/transcribe", JSONObject().put("pcm", Base64.encodeToString(pcm, Base64.NO_WRAP)).put("speaking", said))
        }.onFailure { Log.w(TAG, "talk-over transcribe failed: ${it.message}") }.getOrNull()
        val text = res?.optString("text")
        val forMe = res?.optBoolean("forMe", true) ?: true
        Log.i(TAG, "talk-over heard: \"${text ?: "?"}\" for me: $forMe (${pcm.size / 32} ms of audio, ${System.currentTimeMillis() - t0} ms to judge)")
        main.post {
            val cleaned = text?.replace(Persona.leadingName, "")?.trim().orEmpty()
            when {
                text == null -> { stopForTalkOver(); chime(); listenForRequest(followUp = false) }   // transcription unavailable: just listen now
                cleaned.isBlank() || !forMe -> resumeAfterTalkOver()
                else -> { stopForTalkOver(); tHeard = System.currentTimeMillis(); stopWakeLoop(); ask(cleaned) }
            }
        }
    }

    // ---------- call-style audio while the assistant speaks ----------
    @Volatile var callStyle = true
    private var inCallAudio = false
    @Synchronized private fun enterCallAudio() {
        if (inCallAudio) return
        runCatching {
            audio.mode = AudioManager.MODE_IN_COMMUNICATION
            audio.availableCommunicationDevices.firstOrNull { it.type == android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }?.let { audio.setCommunicationDevice(it) }
            val max = audio.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL)
            if (savedCallVolume == null) savedCallVolume = audio.getStreamVolume(AudioManager.STREAM_VOICE_CALL)
            audio.setStreamVolume(AudioManager.STREAM_VOICE_CALL, maxOf(1, (max * replyVolumeFraction).toInt()), 0)
            inCallAudio = true
        }.onFailure { Log.w(TAG, "call audio: ${it.message}") }
    }
    private var savedCallVolume: Int? = null

    /** Earbuds or headphones connected (Bluetooth or wired), not the car: that's where the user is listening. */
    private fun headsetOn(): Boolean = runCatching {
        if (CarMode.inCar(this)) return false
        val kinds = setOf(android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, android.media.AudioDeviceInfo.TYPE_BLE_HEADSET,
            android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET, android.media.AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            android.media.AudioDeviceInfo.TYPE_USB_HEADSET)
        audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { it.type in kinds }
    }.getOrDefault(false)
    @Synchronized private fun leaveCallAudio() {
        if (!inCallAudio) return
        inCallAudio = false
        runCatching {
            audio.clearCommunicationDevice()
            audio.mode = AudioManager.MODE_NORMAL
            savedCallVolume?.let { audio.setStreamVolume(AudioManager.STREAM_VOICE_CALL, it, 0) }; savedCallVolume = null
        }
    }

    /** Mic levels heard under the assistant's own speech (dB), for measuring the echo canceller. */
    private val echoLevels = java.util.Collections.synchronizedList(ArrayList<Double>())
    private fun logEchoLevels(tag: String) {
        val l = synchronized(echoLevels) { echoLevels.sorted().also { echoLevels.clear() } }
        if (l.size < 5) return
        fun p(q: Double) = l[((l.size - 1) * q).toInt()]
        Log.i(TAG, "echo levels ($tag, ${if (callStyle) "call" else "media"} audio): p50 ${"%.0f".format(p(0.5))} p90 ${"%.0f".format(p(0.9))} max ${"%.0f".format(l.last())} dB over ${l.size} chunks")
    }
    private fun onBargeIn() {
        Log.i(TAG, "interrupted by the user")
        interruptedLast = true
        clips.clear(); replyStreaming = false
        player?.runCatching { stop() }; player?.release(); player = null   // music stays paused: the user is about to talk
        playedThisTurn = false
        stopWakeLoop()
        wakeAt = System.currentTimeMillis()
        chime()
        main.postDelayed({ listenForRequest(followUp = false) }, 150)
    }

    // ---------- 2. hear the request ----------
    private var wakeAt = 0L
    // Where a turn's time goes (logcat "turn:"): the user stops talking -> words -> reply back -> voice starts.
    private var tSpeechEnd = 0L; private var tHeard = 0L; private var tReply = 0L; private var tSpoke = 0L; private var serverMs = ""

    private fun onWake(fromTap: Boolean) {
        if (state == "Thinking" || state == "Speaking") { if (fromTap) stopSpeaking(); return }
        wakeAt = System.currentTimeMillis()
        stopWakeLoop()
        chime()
        main.postDelayed({ listenForRequest(followUp = false) }, 100)
    }

    // ---------- one recogniser, kept warm ----------
    // Building a SpeechRecognizer on every wake costs up to a second or more, so build it once and
    // reuse it. Prefer the on-device recogniser (starts faster, works offline); if it reports it
    // can't serve the language, fall back to the default (cloud) one for good.
    private var preferOnDevice = true

    private fun recognizer(): SpeechRecognizer {
        recognizer?.let { return it }
        val r = if (preferOnDevice && android.os.Build.VERSION.SDK_INT >= 31 &&
            SpeechRecognizer.isOnDeviceRecognitionAvailable(this)) {
            Log.i(TAG, "recogniser: on-device")
            SpeechRecognizer.createOnDeviceSpeechRecognizer(this)
        } else {
            Log.i(TAG, "recogniser: default")
            SpeechRecognizer.createSpeechRecognizer(this)
        }
        recognizer = r
        return r
    }

    private fun dropRecognizer() { recognizer?.destroy(); recognizer = null }

    // ---------- reply volume ----------
    // the assistant plays on the MEDIA stream, which silent/vibrate mode does not mute. What makes the user
    // inaudible is a low media volume, so raise it for the assistant and put it back afterwards.
    private var savedVolume: Int? = null
    private val replyVolumeFraction = 0.6f

    private fun raiseVolume() {
        runCatching {
            val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val cur = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
            val target = maxOf(1, (max * replyVolumeFraction).toInt())
            if (savedVolume == null) savedVolume = cur
            if (cur < target) audio.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
        }.onFailure { Log.w(TAG, "could not raise volume: ${it.message}") }
    }

    private fun restoreVolume() {
        val v = savedVolume ?: return
        savedVolume = null
        runCatching { audio.setStreamVolume(AudioManager.STREAM_MUSIC, v, 0) }
    }

    private fun chime() = runCatching {
        raiseVolume()
        ToneGenerator(AudioManager.STREAM_MUSIC, 60).apply { startTone(ToneGenerator.TONE_PROP_BEEP2, 120) }
            .also { t -> main.postDelayed({ t.release() }, 400) }
    }

    /** Separate recogniser for audio we hand it (talk-over): the on-device one returned "" for piped audio (11-13). */
    private var pipedRecognizer: SpeechRecognizer? = null
    var pipedOnDevice = false

    private fun listenForRequest(followUp: Boolean, audioIn: android.os.ParcelFileDescriptor? = null) {
        if (audioIn == null) earbudMicPipe()?.let { listenForRequest(followUp, it); return }
        val r = if (audioIn == null) recognizer() else (pipedRecognizer ?: (if (pipedOnDevice) SpeechRecognizer.createOnDeviceSpeechRecognizer(this) else SpeechRecognizer.createSpeechRecognizer(this)).also { pipedRecognizer = it })
        r.cancel()
        holdFocus()   // music pauses while the user speaks, and stays paused through the reply
        setState(if (followUp) "Anything else?" else "Listening")
        r.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: Bundle?) {
                feeding = false
                stopEarbudMic()
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                val cleaned = text.replace(Persona.leadingName, "").trim()
                tHeard = System.currentTimeMillis()
                Log.i(TAG, "heard: \"$text\" (${if (tSpeechEnd > 0) tHeard - tSpeechEnd else -1} ms after speech ended)")
                if (cleaned.isNotBlank()) announceRetry = false
                when {
                    cleaned.isNotBlank() -> ask(cleaned)
                    // The user said only the wake phrase (e.g. during the follow-up window): the user wants to say something new.
                    Persona.anyName.containsMatchIn(text) -> { chime(); main.postDelayed({ listenForRequest(followUp = false) }, 150) }
                    else -> backToWake()
                }
            }
            override fun onError(error: Int) {
                feeding = false
                stopEarbudMic()
                Log.i(TAG, "recognizer error $error${if (audioIn != null) " (talk-over audio)" else ""}")
                // Language not available on-device, or the recogniser got wedged: rebuild (cloud if on-device can't serve).
                val onDeviceCantServe = error == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED || error == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE
                if (onDeviceCantServe && preferOnDevice) {
                    // Switch to the cloud recogniser for good and retry this one request (once, by construction).
                    preferOnDevice = false
                    dropRecognizer()
                    if (!followUp) { listenForRequest(followUp = false); return }
                } else if (error == SpeechRecognizer.ERROR_SERVER_DISCONNECTED || error == SpeechRecognizer.ERROR_CLIENT) {
                    dropRecognizer()   // rebuilt fresh on the next wake
                }
                if (followUp && announceRetry && (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT)) {
                    announceRetry = false
                    chime()
                    main.postDelayed({ listenForRequest(followUp = true) }, 250)
                    return
                }
                announceRetry = false
                backToWake()
            }
            override fun onReadyForSpeech(params: Bundle?) {
                if (followUp && tSpoke > 0) Log.i(TAG, "turn: listening again ${System.currentTimeMillis() - tSpoke} ms after the reply ended")
                if (!followUp && wakeAt > 0) Log.i(TAG, "ready for speech ${System.currentTimeMillis() - wakeAt} ms after wake")
            }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() { tSpeechEnd = System.currentTimeMillis() }
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        r.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            if (audioIn != null) {
                // What the user said over the assistant, from the echo-cancelled mic: 16 kHz mono PCM through a pipe.
                putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, audioIn)
                putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
                putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, 16000)
            }
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            // How long a pause ends the request. 700 ms feels instant without cutting people off mid-thought.
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 700L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 500L)
        })
    }

    // ---------- earbud mic ----------
    // With Bluetooth earbuds in, the user talks to the earbuds, not the phone in a pocket. The wake word stays on the
    // phone's mic (an always-open earbud mic would hold Bluetooth in call mode and make music sound like a call),
    // but each request and answer is taken from the earbuds: open the Bluetooth headset mic (call mode, so it
    // brings up the headset's voice link), and pipe it to the recogniser the way talk-over audio is piped.
    @Volatile private var earbudRec: AudioRecord? = null
    private var earbudCall = false

    private fun earbudMicPipe(): android.os.ParcelFileDescriptor? {
        if (CarMode.inCar(this) || checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return null
        val btKinds = setOf(android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO, android.media.AudioDeviceInfo.TYPE_BLE_HEADSET)
        val link = audio.availableCommunicationDevices.firstOrNull { it.type in btKinds } ?: return null
        stopEarbudMic()
        return runCatching {
            val t0 = System.currentTimeMillis()
            audio.mode = AudioManager.MODE_IN_COMMUNICATION
            audio.setCommunicationDevice(link)
            earbudCall = true
            val min = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val rec = AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, 16000, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, maxOf(min, 6400))
            audio.getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull { it.type in btKinds }?.let { rec.setPreferredDevice(it) }
            val pipe = android.os.ParcelFileDescriptor.createPipe()
            earbudRec = rec
            rec.startRecording()
            thread(name = "assistant-earbud-mic") {
                val buf = ShortArray(1280)
                var logged = false
                runCatching {
                    android.os.ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]).use { o ->
                        while (earbudRec === rec) {
                            val n = rec.read(buf, 0, buf.size)
                            if (n <= 0) break
                            if (!logged) { logged = true; Log.i(TAG, "earbud mic: ${rec.routedDevice?.productName ?: "?"} (type ${rec.routedDevice?.type}), first audio ${System.currentTimeMillis() - t0} ms") }
                            o.write(shortsToBytes(buf.copyOf(n)))
                        }
                    }
                }
                runCatching { rec.stop() }; rec.release()
            }
            pipe[0]
        }.onFailure { Log.w(TAG, "earbud mic: ${it.message}"); stopEarbudMic() }.getOrNull()
    }

    private fun stopEarbudMic() {
        earbudRec = null   // the reader thread sees this, closes the pipe and releases the recorder
        if (earbudCall) {
            earbudCall = false
            thread(name = "assistant-earbud-off") { runCatching { if (earbudRec == null && !earbudCall) { audio.clearCommunicationDevice(); if (!inCallAudio) audio.mode = AudioManager.MODE_NORMAL } }
            }   // the check inside skips it if the next listen already reopened the mic
        }
    }

    /** Pause other audio (music apps pause on a transient loss) for the whole exchange, not just the assistant's voice. */
    private fun holdFocus() {
        if (focus != null) return
        val attrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
        focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT).setAudioAttributes(attrs).build()
            .also { audio.requestAudioFocus(it) }
    }
    private fun releaseFocus() { focus?.let { audio.abandonAudioFocusRequest(it) }; focus = null }

    private fun backToWake() {
        typedTurn = false
        stopEarbudMic()
        releaseFocus()   // conversation over: the music comes back
        if (bargeIn) stopWakeLoop()
        restoreVolume()   // conversation over: give the phone its own volume back
        recognizer?.cancel()
        if (running) startWakeLoop()
        main.postDelayed({ drain() }, 600)   // anything that arrived while the user was talking
    }

    // ---------- 3. ask the brain ----------
    private fun ask(text: String, extra: JSONObject? = null) {
        recognizer?.cancel()
        setState("Thinking")
        val fresh = System.currentTimeMillis() - contextAt < contextTtl
        val ctx = extra ?: JSONObject()
        if (fresh && extra == null) {
            bridgeId?.let { ctx.put("bridgeId", it) }
            textFrom?.let { ctx.put("textThread", JSONObject().put("from", it)) }
            contextAt = System.currentTimeMillis()
        } else if (!fresh) { bridgeId = null; textKey = null; textFrom = null }
        replyStreaming = true; playedThisTurn = false
        speaking.setLength(0); talkOverMuted = false; talkPaused = false; quietAfterReply = false
        thread(name = "assistant-ask") {
            // Home commands: understood on the phone, answered from a pre-recorded line, done in the background.
            if (extra == null) FastPath.understand(text)?.let { i ->
                if (i.action == "hush") { main.post { Log.i(TAG, "hush: going quiet"); replyStreaming = false; backToWake() }; return@thread }
                val line = FastPath.line(i)
                val clip = FastPath.Clips.get(this, line)
                if (clip != null) {
                    main.post {
                        tReply = System.currentTimeMillis(); lastReply = line; speaking.append(line)
                        Log.i(TAG, "reply (on the phone): $line")
                        replyStreaming = false
                        speak(clip)
                        runHome(i)
                    }
                    noteToBrain(text, line)
                    return@thread
                }
            }
            val result = runCatching { postChat(text, ctx) }
            main.post {
                result.onSuccess { (reply, audioB64) ->
                    tReply = System.currentTimeMillis()
                    Log.i(TAG, "reply: $reply")
                    lastReply = reply; speaking.setLength(0); speaking.append(reply)
                    // "Shall I send it?": if the user's answer isn't caught in the first window, listen once more.
                    if (reply.trimEnd().endsWith("?")) announceRetry = true
                    replyStreaming = false
                    when {
                        audioB64 != null -> speak(audioB64)
                        talkPaused -> {}   // waiting on the talk-over verdict; it resumes or stops the turn
                        player == null && clips.isEmpty() -> if (playedThisTurn) doneSpeaking(followUpWanted = true) else backToWake()
                        // else: sentences are still playing; the last one ends the turn
                    }
                }.onFailure {
                    Log.e(TAG, "ask failed", it)
                    replyStreaming = false; clips.clear()
                    // The brain is unreachable: say so in the user's voice (pre-recorded), so silence never means "broken".
                    FastPath.Clips.get(this, FastPath.Clips.OFFLINE)?.let { a -> setState("Can't reach ${Persona.NAME}"); speak(a); return@onFailure }
                    setState("Can't reach ${Persona.NAME}: ${it.message}")
                    main.postDelayed({ backToWake() }, 2500)
                }
            }
        }
    }

    /** A home command (TV, volume, app, car mode), done in the background so the reply never waits
     *  on the TV. Only a failure speaks up, once the user is free to hear it. */
    private fun runHome(i: FastPath.Intent) {
        thread(name = "assistant-home") {
            val why = when (i.action) {
                "tv_on" -> SamsungTv.power(this, true)
                "tv_off" -> SamsungTv.power(this, false)
                "open" -> SamsungTv.openApp(this, i.app)
                "volume_set" -> SamsungTv.setVolume(this, level = i.level)
                "volume_up" -> SamsungTv.setVolume(this, delta = i.level.takeIf { it > 0 } ?: 5)
                "volume_down" -> SamsungTv.setVolume(this, delta = -(i.level.takeIf { it > 0 } ?: 5))
                "mute" -> SamsungTv.mute(this, true)
                "pause", "play", "skip_back", "skip_forward", "home", "back", "ok" -> SamsungTv.playback(this, i.action, i.level)
                "unmute" -> SamsungTv.mute(this, false)
                "car_on", "car_off" -> {
                    val on = i.action == "car_on"
                    CarMode.command(this, if (on) "on" else "off")
                    runCatching { postJson("/api/away", JSONObject().put("on", on).put("by", "car")) }.exceptionOrNull()?.message
                }
                else -> null
            } ?: run { Log.i(TAG, "home ${i.action} ${i.app}: done"); return@thread }
            Log.w(TAG, "home ${i.action} ${i.app}: failed: $why")
            lastPhoneFailure = "the ${i.action.replace('_', ' ')} ${i.app} did NOT happen: $why"
            val msg = Persona.say("Sorry%A. ") + why
            val audio = runCatching { speakOnly(msg) }.getOrNull() ?: return@thread
            main.post { enqueue(Announcement.Claude("home", msg, audio)) }
        }
    }

    /** Tell the brain what the phone just did by itself, so the conversation stays one conversation. */
    private fun noteToBrain(said: String, reply: String) = thread(name = "assistant-note") {
        runCatching { postJson("/api/note", JSONObject().put("user", said).put("assistant", reply)) }.onFailure { Log.w(TAG, "note: ${it.message}") }
    }

    @Volatile private var textFailed: String? = null
    /** The brain's history says a text went out; if it didn't, the next request tells it so. */
    @Volatile private var lastPhoneFailure: String? = null

    /** TTS for a line the phone itself needs to say (e.g. a failed send). */
    private fun speakOnly(text: String): String? {
        val key = Prefs.passphrase(this) ?: return null
        val c = URL("${BuildConfig.API_BASE}/api/speak").openConnection() as HttpURLConnection
        c.requestMethod = "POST"; c.connectTimeout = 10_000; c.readTimeout = 20_000; c.doOutput = true
        c.setRequestProperty("content-type", "application/json"); c.setRequestProperty("x-assistant-key", key)
        c.outputStream.use { it.write(JSONObject().put("text", text).toString().toByteArray()) }
        if (c.responseCode !in 200..299) return null
        return JSONObject(c.inputStream.bufferedReader().use { it.readText() }).optString("audio").takeIf { it.isNotBlank() && it != "null" }
    }

    private fun postChat(text: String, extra: JSONObject = JSONObject()): Pair<String, String?> {
        val key = Prefs.passphrase(this) ?: throw IllegalStateException("no access phrase set")
        val c = URL("${BuildConfig.API_BASE}/api/chat").openConnection() as HttpURLConnection
        c.requestMethod = "POST"; c.connectTimeout = 10_000; c.readTimeout = 60_000; c.doOutput = true
        c.setRequestProperty("content-type", "application/json")
        c.setRequestProperty("x-assistant-key", key)
        val request = JSONObject(extra.toString()).put("text", text).put("stream", true)
        lastPhoneFailure?.let { request.put("phoneFailure", it); lastPhoneFailure = null }
        if (interruptedLast) { request.put("interrupted", true); interruptedLast = false }
        runCatching { PhoneCalendar.agenda(this) }.getOrNull()?.let { request.put("calendar", it) }
        val now = System.currentTimeMillis()
        MessageListener.recent().take(8).takeIf { it.isNotEmpty() }?.let { r ->
            request.put("recentTexts", org.json.JSONArray(r.map { t ->
                JSONObject().put("from", t.from).put("minutesAgo", (now - t.at) / 60_000).apply { if (t.missed) put("missed", true).put("text", t.text) }
            }))
        }
        if (runCatching { PhoneCalendar.canWrite(this) }.getOrDefault(false)) request.put("canAddEvents", true)
        runCatching { Calls.recent(this) }.getOrNull()?.let { request.put("recentCalls", it) }   // "who called?"
        c.outputStream.use { it.write(request.toString().toByteArray()) }
        val code = c.responseCode
        if (code == 401) { Prefs.clearPassphrase(this); throw IllegalStateException("access phrase rejected: enter it again in the app") }
        // Streamed: one JSON line per voiced sentence (played as it lands), then a "done" line with the reply.
        val j: JSONObject = if (code in 200..299 && c.contentType.orEmpty().contains("ndjson")) {
            var done: JSONObject? = null
            c.inputStream.bufferedReader().useLines { lines ->
                for (line in lines) {
                    if (line.isBlank()) continue
                    val o = JSONObject(line)
                    when (o.optString("t")) {
                        "audio" -> o.optString("audio").takeIf { it.isNotBlank() }?.let { a ->
                            val said = o.optString("text"); main.post { if (replyStreaming) { if (said.isNotBlank()) speaking.append(said).append(' '); speak(a) } } }
                        "done" -> done = o
                    }
                }
            }
            done ?: throw IllegalStateException("reply cut off")
        } else {
            val body = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            JSONObject(body.ifBlank { "{}" })
        }
        if (code !in 200..299 && !j.has("reply")) throw IllegalStateException("HTTP $code")
        // Carry out anything the brain asked the phone to do (e.g. add a calendar event) before speaking.
        j.optJSONArray("actions")?.let { acts ->
            for (i in 0 until acts.length()) {
                val a = acts.optJSONObject(i) ?: continue
                if (a.optString("type") == "voice_enrol") { main.post { startEnrolment() }; continue }
                if (a.optString("type") == "voice_forget") { speaker?.forget(); Log.i(TAG, "voiceprint deleted"); continue }
                if (a.optString("type") == "send_text") {
                    val open = textKey.takeIf { System.currentTimeMillis() - contextAt < contextTtl }
                    val why = Texting.send(this, a.optString("to"), a.optString("text"), open)
                    if (why == null) Log.i(TAG, "text sent")
                    else {
                        // The brain already wrote a "sent" line: never let the user hear it. Say what really happened.
                        Log.e(TAG, "text not sent: $why")
                        textFailed = Persona.say("I couldn't send that%A: ") + "$why."
                        lastPhoneFailure = "the text to ${a.optString("to")} was NOT sent: $why"
                    }
                    continue
                }
                if (a.optString("type") == "dnd_on") {
                    when (SleepMode.start(this, a.optLong("until_ms"))) {
                        null -> main.post { setState("Sleep mode") }
                        "no permission" -> textFailed = Persona.say("I'll stay quiet%A, but") + " I can't turn on Do Not Disturb yet. Open the ${Persona.NAME} app and tap Allow Do Not Disturb, once."
                        else -> textFailed = Persona.say("I couldn't set that%A: that time has already passed.")
                    }
                    continue
                }
                if (a.optString("type") == "dnd_off") { SleepMode.end(this, "asked"); continue }
                if (a.optString("type") == "texts_heard") { MessageListener.clearMissed(); continue }
                // "Stop listening" / room talk that wasn't for the user: no follow-up listen, no more talk-over this turn.
                if (a.optString("type") == "go_quiet") { quietAfterReply = true; talkOverMuted = true; Log.i(TAG, "going quiet after this reply"); continue }
                if (a.optString("type") == "tv") {
                    val act = a.optString("action")
                    runHome(FastPath.Intent(when (act) { "on" -> "tv_on"; "off" -> "tv_off"; else -> act }, a.optString("app"), a.optInt("level", 5)))
                    continue
                }
                if (a.optString("type") == "amazon_cart") {
                    Shop.openCart(this, a.optString("url"), a.optString("title"))?.let { why ->
                        Log.e(TAG, "amazon: $why"); lastPhoneFailure = "the Amazon page did NOT open: $why"
                        textFailed = Persona.say("I couldn't open Amazon%A: ") + "$why."
                    }
                    continue
                }
                if (a.optString("type") == "car_mode") { CarMode.command(this, a.optString("action"))?.let { Log.w(TAG, "car mode: $it") }; continue }
                if (a.optString("type") == "calendar_add") runCatching { PhoneCalendar.add(this, a) }
                    .onSuccess { Log.i(TAG, "calendar added: $it") }
                    .onFailure { Log.e(TAG, "calendar add failed", it); main.post { setState("Couldn't add to calendar: ${it.message}") } }
            }
        }
        textFailed?.let { msg ->
            textFailed = null
            main.post { setState("Couldn't do that") }
            return msg to runCatching { speakOnly(msg) }.getOrNull()
        }
        serverMs = j.optJSONObject("timing")?.let { "brain ${it.optLong("brainMs")} + voice ${it.optLong("voiceMs")}" } ?: "?"
        return j.optString("reply") to j.optString("audio").takeIf { it.isNotBlank() && it != "null" }
    }

    // ---------- 4. speak, then one follow-up ----------
    private val clips = ArrayDeque<String>()
    /** A reply is still arriving: an empty queue means "wait for the next sentence", not "done". */
    @Volatile private var replyStreaming = false
    private var playedThisTurn = false

    private fun speak(b64: String) {
        clips.addLast(b64)
        if (player == null && !talkPaused) playNext()
    }

    private fun playNext() {
        val b64 = clips.removeFirstOrNull() ?: run { if (!replyStreaming) doneSpeaking(followUpWanted = true); return }
        setState("Speaking")
        val f = File(cacheDir, "reply-${System.nanoTime()}.mp3").apply { writeBytes(Base64.decode(b64, Base64.DEFAULT)) }
        // Earbuds or headphones on: speak in them, as media, the way music plays. Call-style below forces the
        // phone's own speaker, and the assistant's voice can't echo off earbuds into the mic.
        val headset = headsetOn()
        if (!headset) raiseVolume()   // the user's earbud volume is theirs; don't push it up
        // Call-style playback: the phone's echo canceller only knows what to remove when the sound is played as a
        // call. As media (USAGE_MEDIA) the assistant's own voice came back through the "echo-cancelled" mic at 70-79 dB,
        // as loud as the user, so the user could not talk over it (11-13).
        val call = callStyle && !headset
        if (call) enterCallAudio() else leaveCallAudio()
        val attrs = AudioAttributes.Builder().setUsage(if (call) AudioAttributes.USAGE_VOICE_COMMUNICATION else AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
        // GAIN_TRANSIENT (not MAY_DUCK): music apps pause while the assistant speaks and resume when it gives
        // focus back in doneSpeaking.
        if (focus == null) focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT).setAudioAttributes(attrs).build()
            .also { audio.requestAudioFocus(it) }
        player?.release()
        player = MediaPlayer().apply {
            setAudioAttributes(attrs)
            setDataSource(f.absolutePath)
            setOnCompletionListener { it.release(); player = null; f.delete(); playNext() }
            setOnErrorListener { _, _, _ -> f.delete(); clips.clear(); replyStreaming = false; doneSpeaking(followUpWanted = false); true }
            prepare(); start()
        }
        if (!listening) startWakeLoop(barge = true)
        if (!playedThisTurn) {
            playedThisTurn = true
            val now = System.currentTimeMillis()
            if (tHeard > 0) Log.i(TAG, "turn: words ${if (tSpeechEnd in 1..tHeard) tHeard - tSpeechEnd else -1} ms, " +
                "first sound ${now - tHeard} ms after the user's words (reply ${if (tReply >= tHeard) "complete at ${tReply - tHeard} ms" else "still arriving"})")
            tHeard = 0; tSpeechEnd = 0
        }
    }

    private fun stopSpeaking() { clips.clear(); replyStreaming = false; player?.stop(); doneSpeaking(followUpWanted = false) }

    private fun doneSpeaking(followUpWanted: Boolean) {
        val followUp = followUpWanted && !quietAfterReply
        quietAfterReply = false
        tSpoke = System.currentTimeMillis()
        if (bargeIn) stopWakeLoop()   // free the mic for the follow-up listen
        player?.release(); player = null
        playedThisTurn = false
        if (!followUp) restoreVolume()
        val typed = typedTurn
        typedTurn = false
        // Keep the music paused while the user answers; backToWake gives it back.
        if (!(followUp && !typed)) releaseFocus()
        if (followUp && !typed) {
            // Listen FIRST, leave call mode after: switching the phone out of call mode takes ~1 s on this
            // the phone, and on 11-13 the user's quick "yes" to "Shall I send it?" fell into that gap twice.
            listenForRequest(followUp = true)
            main.postDelayed({ leaveCallAudioAsync() }, 50)
        } else { leaveCallAudioAsync(); backToWake() }
    }

    /** Leave call mode off the main thread (the mode switch blocks for ~1 s). */
    private fun leaveCallAudioAsync() { if (inCallAudio) thread(name = "assistant-audio-mode") { leaveCallAudio() } }

    // ---------- debug hooks (adb) ----------
    private val debugReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.action) {
                DEBUG_ASK -> intent.getStringExtra("text")?.let { t -> stopWakeLoop(); ask(t) }
                DEBUG_SCORE -> intent.getStringExtra("file")?.let { name -> thread { scoreFile(File(filesDir, name)) } }
            }
        }
    }

    /** Runs a raw 16 kHz PCM16 file through a fresh detector and logs the best score. */
    private fun scoreFile(f: File) {
        val bytes = f.readBytes()
        val pcm = ShortArray(bytes.size / 2) { ((bytes[it * 2 + 1].toInt() shl 8) or (bytes[it * 2].toInt() and 0xff)).toShort() }
        val padded = ShortArray(16000) + pcm + ShortArray(16000)
        WakeDetector(this, listOf("wakeword.onnx")).use { d ->
            var best = 0f; var i = 0
            while (i + WakeDetector.CHUNK <= padded.size) { best = maxOf(best, d.push(padded.copyOfRange(i, i + WakeDetector.CHUNK))); i += WakeDetector.CHUNK }
            Log.i(TAG, "score ${f.name} = $best")
        }
    }
}
