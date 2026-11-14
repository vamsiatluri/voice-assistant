package org.voiceassistant

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import java.nio.FloatBuffer
import kotlin.random.Random

/**
 * openWakeWord streaming pipeline (port of openwakeword/utils.py AudioFeatures), fed one
 * 1280-sample (80 ms, 16 kHz, PCM16) chunk at a time. Verified against a Node reference
 * implementation before porting: the wake phrase scores >= 0.998 across six voices.
 */
class WakeDetector(context: Context, wakeModels: List<String>) : AutoCloseable {
    companion object {
        const val CHUNK = 1280
        private const val MEL_CONTEXT = 480       // 3 extra hops of context, as openWakeWord does
        private const val MAX_MEL_FRAMES = 970
        private const val MAX_EMBEDS = 120
    }

    private val env = OrtEnvironment.getEnvironment()
    private val opts = OrtSession.SessionOptions().apply { setIntraOpNumThreads(1); setInterOpNumThreads(1) }
    private fun load(ctx: Context, name: String) = env.createSession(ctx.assets.open(name).use { it.readBytes() }, opts)

    private val mel = load(context, "melspectrogram.onnx")
    private val emb = load(context, "embedding_model.onnx")
    private val wake: Map<String, OrtSession> = wakeModels.associateWith { load(context, it) }

    private var raw = ShortArray(0)
    private val melFrames = ArrayDeque<FloatArray>()
    private val embeds = ArrayDeque<FloatArray>()
    private var scoresSeen = 0

    init {
        repeat(76) { melFrames.addLast(FloatArray(32) { 1f }) }
        // Warm the embedding history with 4 s of low noise, exactly like openWakeWord.
        val noise = ShortArray(16000 * 4) { (Random.nextInt(2000) - 1000).toShort() }
        var i = 0
        while (i + CHUNK <= noise.size) { push(noise.copyOfRange(i, i + CHUNK), score = false); i += CHUNK }
        scoresSeen = 0
    }

    /** Returns the highest wake-model score for this chunk (0..1). */
    fun push(chunk: ShortArray, score: Boolean = true): Float {
        // rolling raw buffer (only the last chunk + context is ever needed)
        val keep = CHUNK + MEL_CONTEXT
        val merged = ShortArray(raw.size + chunk.size)
        raw.copyInto(merged); chunk.copyInto(merged, raw.size)
        raw = if (merged.size > keep) merged.copyOfRange(merged.size - keep, merged.size) else merged

        // 1. melspectrogram over the newest window
        val win = FloatArray(raw.size) { raw[it].toFloat() }
        OnnxTensor.createTensor(env, FloatBuffer.wrap(win), longArrayOf(1, win.size.toLong())).use { t ->
            mel.run(mapOf("input" to t)).use { r ->
                val out = r[0] as OnnxTensor
                val shape = out.info.shape
                val frames = shape[shape.size - 2].toInt()
                val buf = out.floatBuffer
                for (f in 0 until frames) melFrames.addLast(FloatArray(32) { k -> buf.get(f * 32 + k) / 10f + 2f })
            }
        }
        while (melFrames.size > MAX_MEL_FRAMES) melFrames.removeFirst()

        // 2. one 96-d embedding from the last 76 mel frames
        val w = FloatArray(76 * 32)
        val start = melFrames.size - 76
        for (i in 0 until 76) melFrames[start + i].copyInto(w, i * 32)
        OnnxTensor.createTensor(env, FloatBuffer.wrap(w), longArrayOf(1, 76, 32, 1)).use { t ->
            emb.run(mapOf("input_1" to t)).use { r ->
                val b = (r[0] as OnnxTensor).floatBuffer
                embeds.addLast(FloatArray(96) { b.get(it) })
            }
        }
        while (embeds.size > MAX_EMBEDS) embeds.removeFirst()
        if (!score) return 0f

        // 3. wake models over the last 16 embeddings
        val feat = FloatArray(16 * 96)
        val es = embeds.size - 16
        for (i in 0 until 16) embeds[es + i].copyInto(feat, i * 96)
        var best = 0f
        OnnxTensor.createTensor(env, FloatBuffer.wrap(feat), longArrayOf(1, 16, 96)).use { t ->
            for ((_, s) in wake) {
                s.run(mapOf(s.inputNames.first() to t)).use { r ->
                    best = maxOf(best, (r[0] as OnnxTensor).floatBuffer.get(0))
                }
            }
        }
        return if (scoresSeen++ < 5) 0f else best
    }

    /** Forget recent audio so the tail of the last reply cannot re-trigger the wake word. */
    fun clearHistory() {
        raw = ShortArray(0)
        scoresSeen = -11   // the 16-embedding window still holds old audio: stay deaf for ~1.3 s
    }

    override fun close() {
        wake.values.forEach { it.close() }; emb.close(); mel.close(); opts.close()
    }
}
