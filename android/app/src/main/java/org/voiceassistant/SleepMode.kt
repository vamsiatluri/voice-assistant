package org.voiceassistant

import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * "I'm going to sleep, don't disturb me till 6": Do Not Disturb on until then, the assistant silent too.
 * Uses the phone's own DND (priority mode), so the user's DND exceptions (alarms, starred contacts,
 * repeat callers) still apply. An alarm turns it back off at the time; we only ever turn off a
 * DND that we turned on.
 */
object SleepMode {
    private const val TAG = "Assistant"
    private fun p(c: Context) = c.getSharedPreferences("assistant", Context.MODE_PRIVATE)

    fun until(c: Context): Long = p(c).getLong("sleepUntil", 0L)
    fun asleep(c: Context) = until(c) > System.currentTimeMillis()
    fun canControlDnd(c: Context) = c.getSystemService(NotificationManager::class.java).isNotificationPolicyAccessGranted

    /** Returns null on success, otherwise why it couldn't. */
    fun start(c: Context, untilMs: Long): String? {
        if (untilMs <= System.currentTimeMillis()) return "that time has already passed"
        val nm = c.getSystemService(NotificationManager::class.java)
        if (!nm.isNotificationPolicyAccessGranted) {
            // Still go quiet ourselves; only the system DND needs the permission.
            p(c).edit().putLong("sleepUntil", untilMs).putBoolean("dndByUs", false).apply()
            schedule(c, untilMs)
            return "no permission"
        }
        val alreadyOn = nm.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL
        if (!alreadyOn) nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY)
        p(c).edit().putLong("sleepUntil", untilMs).putBoolean("dndByUs", !alreadyOn || p(c).getBoolean("dndByUs", false)).apply()
        schedule(c, untilMs)
        Log.i(TAG, "sleep mode on until $untilMs (dnd ${if (alreadyOn) "was already on" else "turned on"})")
        return null
    }

    fun end(c: Context, why: String) {
        val byUs = p(c).getBoolean("dndByUs", false)
        p(c).edit().putLong("sleepUntil", 0L).putBoolean("dndByUs", false).apply()
        c.getSystemService(AlarmManager::class.java).cancel(alarm(c))
        val nm = c.getSystemService(NotificationManager::class.java)
        if (byUs && nm.isNotificationPolicyAccessGranted) nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
        Log.i(TAG, "sleep mode off ($why), dnd ${if (byUs) "turned off" else "left as it was"}")
    }

    /** After a reboot or a missed alarm: if the time has passed, finish the job. */
    fun catchUp(c: Context) {
        val u = until(c)
        if (u in 1..System.currentTimeMillis()) end(c, "time passed")
        else if (u > 0) schedule(c, u)
    }

    private fun schedule(c: Context, at: Long) {
        val am = c.getSystemService(AlarmManager::class.java)
        if (am.canScheduleExactAlarms()) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, alarm(c))
        else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, alarm(c))
    }

    private fun alarm(c: Context) = PendingIntent.getBroadcast(c, 7, Intent(c, SleepEndReceiver::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
}

class SleepEndReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) = SleepMode.end(c, "alarm")
}
