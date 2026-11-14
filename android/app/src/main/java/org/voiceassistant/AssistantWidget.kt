package org.voiceassistant

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews

/** Home-screen / lock-screen (via Good Lock LockStar) widget: orb + status. Tap = talk. */
class AssistantWidget : AppWidgetProvider() {
    companion object {
        const val ACTION_REFRESH = "org.voiceassistant.WIDGET_REFRESH"

        fun refresh(ctx: Context, state: String, lastReply: String?) {
            val mgr = AppWidgetManager.getInstance(ctx)
            val ids = mgr.getAppWidgetIds(ComponentName(ctx, AssistantWidget::class.java))
            if (ids.isEmpty()) return
            val label = when {
                !AssistantService.running -> "Off. Tap to start"
                state.startsWith("Listening for") -> "Say \"${Persona.WAKE_PHRASE}\""
                else -> state
            }
            val tap = if (AssistantService.running)
                PendingIntent.getService(ctx, 10, Intent(ctx, AssistantService::class.java).setAction(AssistantService.ACTION_TALK), PendingIntent.FLAG_IMMUTABLE)
            else
                PendingIntent.getActivity(ctx, 11, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
            val views = RemoteViews(ctx.packageName, R.layout.widget).apply {
                setTextViewText(R.id.widget_state, label)
                setTextViewText(R.id.widget_reply, lastReply ?: "")
                setOnClickPendingIntent(R.id.widget_root, tap)
            }
            mgr.updateAppWidget(ids, views)
        }
    }

    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        refresh(ctx, AssistantService.state, null)
    }
}
