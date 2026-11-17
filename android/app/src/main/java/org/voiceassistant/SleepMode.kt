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

    /**
     * asked = the owner said to (good morning / turn DND off): DND goes off whoever turned it on (a phone's own
     * bedtime mode often has). The alarm only undoes our own. Returns null when DND is off afterwards,
     * otherwise what to tell them.
     */
    fun end(c: Context, why: String, asked: Boolean = false): String? {
        val byUs = p(c).getBoolean("dndByUs", false)
        p(c).edit().putLong("sleepUntil", 0L).putBoolean("dndByUs", false).apply()
        c.getSystemService(AlarmManager::class.java).cancel(alarm(c))
        val nm = c.getSystemService(NotificationManager::class.java)
        val on = { nm.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL }
        if (!(byUs || (asked && on()))) {
            Log.i(TAG, "sleep mode off ($why), dnd left as it was")
            return null
        }
        if (!nm.isNotificationPolicyAccessGranted) return "I can't turn off Do Not Disturb yet. Open the app and tap Allow Do Not Disturb, once."
        nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
        // Android lets an app switch off only a DND it turned on; one from the phone's bedtime mode or a routine stays.
        var left = on()
        for (i in 0 until 6) { if (!left) break; Thread.sleep(150); left = on() }
        Log.i(TAG, "sleep mode off ($why), dnd ${if (left) "STILL ON (not ours to turn off)" else "turned off"}")
        return if (left) "Do Not Disturb is still on: the phone's own bedtime mode turned it on, and it won't let me turn that one off. Swipe down and tap Do Not Disturb." else null
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
    override fun onReceive(c: Context, i: Intent) { SleepMode.end(c, "alarm") }
}
