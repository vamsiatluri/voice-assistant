package org.voiceassistant

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * "Is this the owner?" — a voiceprint from the wake phrase the user just said.
 *
 * Model: 3D-Speaker CAM++ (English, VoxCeleb), Apache-2.0, from the sherpa-onnx model release.
 * Input is Kaldi-style 80-bin log-mel fbank (25 ms / 10 ms, povey window, pre-emphasis 0.97,
 * DC removed, samples in [-1, 1]) with the per-utterance mean subtracted; output is a 512-d
 * embedding. The fbank here was checked against sherpa-onnx's own extractor (same scores
 * to two decimals) before it was ported.
 *
 * Enrolment = the mean of several wake phrases; verification = cosine similarity to it.
 */
class SpeakerId(private val ctx: Context) : AutoCloseable {
    companion object {
        const val ENROL_SAMPLES = 5
        /** Cosine score at or above which a wake counts as the user. Tuned from the user's own enrolment. */
        const val DEFAULT_THRESHOLD = 0.45f
        private const val SR = 16000
        private const val FRAME = 400
        private const val SHIFT = 160
        private const val NFFT = 512
        private const val NMEL = 80
    }

    private val env = OrtEnvironment.getEnvironment()
    private val opts = OrtSession.SessionOptions().apply { setIntraOpNumThreads(2) }
    private val session: OrtSession = env.createSession(ctx.assets.open("speaker_campplus_en.onnx").use { it.readBytes() }, opts)
    private val file = File(ctx.filesDir, "voiceprint.bin")
    private val window = FloatArray(FRAME) { (0.5 - 0.5 * cos(2 * PI * it / (FRAME - 1))).pow(0.85).toFloat() }
    private val melBank: Array<FloatArray> = buildMelBank()

    var voiceprint: FloatArray? = load(); private set
    val enrolled get() = voiceprint != null
    val threshold: Float get() = Prefs.voiceThreshold(ctx) ?: DEFAULT_THRESHOLD

    /** Unit-length embedding of 16 kHz PCM16 audio. */
    fun embed(pcm: ShortArray): FloatArray {
        val feats = fbank(pcm)
        val frames = feats.size / NMEL
        OnnxTensor.createTensor(env, FloatBuffer.wrap(feats), longArrayOf(1, frames.toLong(), NMEL.toLong())).use { t ->
            session.run(mapOf("x" to t)).use { r ->
                val b = (r[0] as OnnxTensor).floatBuffer
                val e = FloatArray(b.remaining()) { b.get(it) }
                return normalise(e)
            }
        }
    }

    fun score(pcm: ShortArray): Float {
        val vp = voiceprint ?: return 1f
        return dot(vp, embed(pcm))
    }

    /** Builds the voiceprint from enrolment samples. Returns the samples' mean similarity to it. */
    fun enrol(samples: List<FloatArray>): Float {
        val mean = FloatArray(samples[0].size)
        for (s in samples) for (i in mean.indices) mean[i] += s[i]
        val vp = normalise(mean)
        save(vp); voiceprint = vp
        return samples.map { dot(vp, it) }.average().toFloat()
    }

    fun forget() { file.delete(); voiceprint = null }

    // ---------- Kaldi-compatible fbank ----------
    private fun fbank(pcm: ShortArray): FloatArray {
        val n = if (pcm.size < FRAME) 0 else 1 + (pcm.size - FRAME) / SHIFT
        val out = FloatArray(n * NMEL)
        val re = DoubleArray(NFFT); val im = DoubleArray(NFFT); val fr = DoubleArray(FRAME)
        val power = DoubleArray(NFFT / 2 + 1)
        for (f in 0 until n) {
            var mean = 0.0
            for (i in 0 until FRAME) { fr[i] = pcm[f * SHIFT + i] / 32768.0; mean += fr[i] }
            mean /= FRAME
            for (i in 0 until FRAME) fr[i] -= mean
            for (i in FRAME - 1 downTo 1) fr[i] -= 0.97 * fr[i - 1]
            fr[0] -= 0.97 * fr[0]
            java.util.Arrays.fill(re, 0.0); java.util.Arrays.fill(im, 0.0)
            for (i in 0 until FRAME) re[i] = fr[i] * window[i]
            fft(re, im)
            for (k in power.indices) power[k] = re[k] * re[k] + im[k] * im[k]
            for (m in 0 until NMEL) {
                var e = 0.0
                val w = melBank[m]
                for (k in power.indices) if (w[k] != 0f) e += w[k] * power[k]
                out[f * NMEL + m] = ln(max(e, 1.1920929e-07)).toFloat()
            }
        }
        // per-utterance mean normalisation ("global-mean" in the model metadata)
        for (m in 0 until NMEL) {
            var s = 0.0
            for (f in 0 until n) s += out[f * NMEL + m]
            val mu = (s / max(n, 1)).toFloat()
            for (f in 0 until n) out[f * NMEL + m] -= mu
        }
        return out
    }

    private fun buildMelBank(): Array<FloatArray> {
        fun mel(f: Double) = 1127.0 * ln(1.0 + f / 700.0)
        val lo = mel(20.0); val hi = mel(SR / 2.0); val d = (hi - lo) / (NMEL + 1)
        return Array(NMEL) { m ->
            val l = lo + m * d; val c = lo + (m + 1) * d; val r = lo + (m + 2) * d
            FloatArray(NFFT / 2 + 1) { k ->
                val mf = mel(k * SR.toDouble() / NFFT)
                if (mf <= l || mf >= r) 0f else min((mf - l) / (c - l), (r - mf) / (r - c)).toFloat()
            }
        }
    }

    /** In-place iterative radix-2 FFT. */
    private fun fft(re: DoubleArray, im: DoubleArray) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) { var t = re[i]; re[i] = re[j]; re[j] = t; t = im[i]; im[i] = im[j]; im[j] = t }
        }
        var len = 2
        while (len <= n) {
            val ang = -2 * PI / len
            val wr = cos(ang); val wi = sin(ang)
            var i = 0
            while (i < n) {
                var cr = 1.0; var ci = 0.0
                for (k in 0 until len / 2) {
                    val ur = re[i + k]; val ui = im[i + k]
                    val vr = re[i + k + len / 2] * cr - im[i + k + len / 2] * ci
                    val vi = re[i + k + len / 2] * ci + im[i + k + len / 2] * cr
                    re[i + k] = ur + vr; im[i + k] = ui + vi
                    re[i + k + len / 2] = ur - vr; im[i + k + len / 2] = ui - vi
                    val nr = cr * wr - ci * wi; ci = cr * wi + ci * wr; cr = nr
                }
                i += len
            }
            len = len shl 1
        }
    }

    private fun normalise(e: FloatArray): FloatArray {
        var s = 0.0; for (v in e) s += v * v
        val n = sqrt(s).toFloat().takeIf { it > 0 } ?: 1f
        return FloatArray(e.size) { e[it] / n }
    }
    private fun dot(a: FloatArray, b: FloatArray): Float { var s = 0f; for (i in a.indices) s += a[i] * b[i]; return s }

    private fun save(v: FloatArray) {
        val bb = ByteBuffer.allocate(v.size * 4).order(ByteOrder.LITTLE_ENDIAN); v.forEach { bb.putFloat(it) }
        file.writeBytes(bb.array())
    }
    private fun load(): FloatArray? = file.takeIf { it.exists() }?.readBytes()?.let { b ->
        val bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN); FloatArray(b.size / 4) { bb.getFloat() }
    }

    override fun close() { session.close(); opts.close() }
}
