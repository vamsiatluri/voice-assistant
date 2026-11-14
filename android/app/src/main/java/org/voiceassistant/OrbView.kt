package org.voiceassistant

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import kotlin.math.min

/**
 * The the assistant HUD orb, from the same rendered frames as the lock-screen widget
 * (pre-rendered frames in res/drawable-nodpi). Design rule: it only MOVES while the assistant is thinking or
 * replying. Waiting = still; listening = brighter but still; off = faded still.
 */
class OrbView(context: Context) : View(context) {
    enum class Mode { OFF, IDLE, LISTENING, THINKING, SPEAKING }
    var mode = Mode.OFF
        set(v) { field = v; invalidate() }

    private val still: Bitmap = BitmapFactory.decodeResource(resources, R.drawable.orb_widget)
    private val listen: Bitmap = BitmapFactory.decodeResource(resources, R.drawable.orb_listen)
    private val active = listOf(
        R.drawable.orb_pulse_0, R.drawable.orb_pulse_1, R.drawable.orb_pulse_2, R.drawable.orb_pulse_3,
        R.drawable.orb_pulse_4, R.drawable.orb_pulse_5, R.drawable.orb_pulse_6, R.drawable.orb_pulse_7,
    ).map { BitmapFactory.decodeResource(resources, it) }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val dst = RectF()
    private val t0 = System.nanoTime()

    override fun onDraw(c: Canvas) {
        val ms = (System.nanoTime() - t0) / 1_000_000
        val moving = mode == Mode.THINKING || mode == Mode.SPEAKING
        val (bmp, alpha) = when (mode) {
            Mode.OFF -> still to 90
            Mode.IDLE -> still to 255
            Mode.LISTENING -> listen to 255
            Mode.THINKING -> active[((ms / 90) % active.size).toInt()] to 255
            Mode.SPEAKING -> active[((ms / 70) % active.size).toInt()] to 255
        }
        val s = min(width, height) * 0.92f
        dst.set((width - s) / 2, (height - s) / 2, (width + s) / 2, (height + s) / 2)
        paint.alpha = alpha
        c.drawBitmap(bmp, null, dst, paint)
        if (moving && isShown) postInvalidateOnAnimation()   // only animate while moving: no idle redraws
    }
}
