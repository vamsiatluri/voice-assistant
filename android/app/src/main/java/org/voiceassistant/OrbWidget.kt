package org.voiceassistant

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews

/**
 * Round HUD orb, no text. Still while waiting; brighter (still) while listening; moving only while
 * thinking or replying (self-flipping frames, no per-frame updates). Faded when the assistant is off. Tap = talk.
 */
class OrbWidget : AppWidgetProvider() {
    companion object {
        private fun isMoving(state: String) = state == "Thinking" || state == "Speaking"
        private fun isListening(state: String) = state == "Listening" || state == "Anything else?"

        fun refresh(ctx: Context, state: String) {
            val mgr = AppWidgetManager.getInstance(ctx)
            val ids = mgr.getAppWidgetIds(ComponentName(ctx, OrbWidget::class.java))
            if (ids.isEmpty()) return
            val running = AssistantService.running
            val moving = running && isMoving(state)
            val listening = running && isListening(state)
            val tap = if (AssistantService.running)
                PendingIntent.getService(ctx, 20, Intent(ctx, AssistantService::class.java).setAction(AssistantService.ACTION_TALK), PendingIntent.FLAG_IMMUTABLE)
            else
                PendingIntent.getActivity(ctx, 21, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
            val views = RemoteViews(ctx.packageName, R.layout.widget_orb).apply {
                setViewVisibility(R.id.orb_pulse, if (moving) View.VISIBLE else View.GONE)
                setViewVisibility(R.id.orb_listen, if (listening) View.VISIBLE else View.GONE)
                setViewVisibility(R.id.orb_img, if (!moving && !listening) View.VISIBLE else View.GONE)
                setInt(R.id.orb_img, "setImageAlpha", if (running) 255 else 90)
                setOnClickPendingIntent(R.id.orb_root, tap)
            }
            mgr.updateAppWidget(ids, views)
        }
    }

    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) = refresh(ctx, AssistantService.state)
}
