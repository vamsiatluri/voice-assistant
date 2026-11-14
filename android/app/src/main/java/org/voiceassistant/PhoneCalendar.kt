package org.voiceassistant

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Reads the calendar the phone already syncs (Google, Outlook, Samsung...) on the device itself.
 * Returns a compact plain-text agenda for the next [days] days, or null without permission.
 * Nothing here is stored anywhere; it rides along with one question and is gone.
 */
object PhoneCalendar {
    fun agenda(ctx: Context, days: Int = 7, max: Int = 40): String? {
        if (ctx.checkSelfPermission(Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED) return null
        val now = System.currentTimeMillis()
        val start = now - 2 * 3600_000L                      // include something that started recently
        val end = now + days * 86_400_000L
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon()
            .appendPath(start.toString()).appendPath(end.toString()).build()
        val cols = arrayOf(
            CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN, CalendarContract.Instances.END,
            CalendarContract.Instances.ALL_DAY, CalendarContract.Instances.EVENT_LOCATION,
            CalendarContract.Instances.CALENDAR_DISPLAY_NAME, CalendarContract.Instances.SELF_ATTENDEE_STATUS,
        )
        val day = SimpleDateFormat("EEE MMM d", Locale.US)
        val time = SimpleDateFormat("h:mm a", Locale.US)
        val utcDay = SimpleDateFormat("EEE MMM d", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        val lines = mutableListOf<String>()
        ctx.contentResolver.query(uri, cols,
            "${CalendarContract.Instances.SELF_ATTENDEE_STATUS} != ${CalendarContract.Attendees.ATTENDEE_STATUS_DECLINED}",
            null, "${CalendarContract.Instances.BEGIN} ASC")?.use { c ->
            while (c.moveToNext() && lines.size < max) {
                val title = c.getString(0)?.trim().orEmpty().ifEmpty { "(no title)" }
                val b = c.getLong(1); val e = c.getLong(2); val allDay = c.getInt(3) == 1
                if (!allDay && e < now) continue
                val loc = c.getString(4)?.trim().orEmpty()
                val cal = c.getString(5)?.trim().orEmpty()
                val whenText = if (allDay) "${utcDay.format(Date(b))}, all day"
                    else "${day.format(Date(b))} ${time.format(Date(b))}-${time.format(Date(e))}"
                lines += buildString {
                    append("- ").append(whenText).append(": ").append(title)
                    if (loc.isNotEmpty()) append(" @ ").append(loc.take(60))
                    if (cal.isNotEmpty()) append(" [").append(cal.take(30)).append("]")
                }
            }
        }
        val tz = TimeZone.getDefault().id
        return if (lines.isEmpty()) "No events in the next $days days (times in $tz)."
        else "Next $days days (times in $tz):\n" + lines.joinToString("\n")
    }

    fun canWrite(ctx: Context) = ctx.checkSelfPermission(Manifest.permission.WRITE_CALENDAR) == PackageManager.PERMISSION_GRANTED &&
        writableCalendarId(ctx) != null

    /** The phone's primary writable calendar (normally the Google account's own calendar). */
    private fun writableCalendarId(ctx: Context): Long? {
        if (ctx.checkSelfPermission(Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED) return null
        val cols = arrayOf(CalendarContract.Calendars._ID, CalendarContract.Calendars.IS_PRIMARY, CalendarContract.Calendars.ACCOUNT_TYPE)
        var best: Long? = null; var bestScore = -1
        ctx.contentResolver.query(CalendarContract.Calendars.CONTENT_URI, cols,
            "${CalendarContract.Calendars.VISIBLE} = 1 AND ${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL} >= ${CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR}",
            null, null)?.use { c ->
            while (c.moveToNext()) {
                val score = (if (c.getInt(1) == 1) 2 else 0) + (if (c.getString(2) == "com.google") 1 else 0)
                if (score > bestScore) { bestScore = score; best = c.getLong(0) }
            }
        }
        return best
    }

    /** Adds an event (with an alert) from the brain's add_calendar_event action. Returns a short description, or throws. */
    fun add(ctx: Context, a: org.json.JSONObject): String {
        val calId = writableCalendarId(ctx) ?: error("no writable calendar on this phone")
        val title = a.optString("title").ifBlank { "Reminder" }
        val allDay = a.optBoolean("all_day") || !a.optString("start").contains("T")
        val zone = java.time.ZoneId.systemDefault()
        val values = android.content.ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, calId)
            put(CalendarContract.Events.TITLE, title)
            a.optString("location").takeIf { it.isNotBlank() }?.let { put(CalendarContract.Events.EVENT_LOCATION, it) }
            put(CalendarContract.Events.DESCRIPTION, listOf(a.optString("notes"), "Added by ${Persona.NAME}").filter { it.isNotBlank() }.joinToString("\n"))
            if (allDay) {
                val d = java.time.LocalDate.parse(a.optString("start").take(10))
                val endD = a.optString("end").takeIf { it.length >= 10 }?.let { java.time.LocalDate.parse(it.take(10)).plusDays(1) } ?: d.plusDays(1)
                put(CalendarContract.Events.ALL_DAY, 1)
                put(CalendarContract.Events.EVENT_TIMEZONE, "UTC")
                put(CalendarContract.Events.DTSTART, d.atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli())
                put(CalendarContract.Events.DTEND, endD.atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli())
            } else {
                val start = java.time.LocalDateTime.parse(a.optString("start").take(16)).atZone(zone)
                val end = a.optString("end").takeIf { it.contains("T") }?.let { java.time.LocalDateTime.parse(it.take(16)).atZone(zone) }
                    ?.takeIf { it.isAfter(start) } ?: start.plusMinutes(30)
                put(CalendarContract.Events.EVENT_TIMEZONE, zone.id)
                put(CalendarContract.Events.DTSTART, start.toInstant().toEpochMilli())
                put(CalendarContract.Events.DTEND, end.toInstant().toEpochMilli())
            }
        }
        val uri = ctx.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values) ?: error("calendar insert failed")
        val eventId = uri.lastPathSegment!!.toLong()
        val minutes = a.optInt("reminder_minutes", 10).coerceIn(0, 40320)
        ctx.contentResolver.insert(CalendarContract.Reminders.CONTENT_URI, android.content.ContentValues().apply {
            put(CalendarContract.Reminders.EVENT_ID, eventId)
            put(CalendarContract.Reminders.MINUTES, minutes)
            put(CalendarContract.Reminders.METHOD, CalendarContract.Reminders.METHOD_ALERT)
        })
        return "$title (${a.optString("start")}), alert $minutes min before"
    }
}
