package org.voiceassistant

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CallLog
import org.json.JSONArray
import org.json.JSONObject

/**
 * "Who called?": missed, declined and voicemail calls from the phone's call log (last 24 h), with the
 * contact name when there is one. Sent with each question so the assistant can answer and brief from it.
 * Read only; nothing is sent anywhere but the brain.
 */
object Calls {
    fun recent(c: Context, hours: Int = 24, max: Int = 10): JSONArray? {
        if (c.checkSelfPermission(Manifest.permission.READ_CALL_LOG) != PackageManager.PERMISSION_GRANTED) return null
        val since = System.currentTimeMillis() - hours * 3_600_000L
        val out = JSONArray()
        runCatching {
            c.contentResolver.query(CallLog.Calls.CONTENT_URI,
                arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.CACHED_NAME, CallLog.Calls.TYPE, CallLog.Calls.DATE, CallLog.Calls.DURATION, CallLog.Calls.NEW),
                "${CallLog.Calls.DATE} >= ? AND ${CallLog.Calls.TYPE} IN (?, ?, ?)",
                arrayOf(since.toString(), CallLog.Calls.MISSED_TYPE.toString(), CallLog.Calls.REJECTED_TYPE.toString(), CallLog.Calls.VOICEMAIL_TYPE.toString()),
                "${CallLog.Calls.DATE} DESC")?.use { cur ->
                val now = System.currentTimeMillis()
                while (cur.moveToNext() && out.length() < max) {
                    val name = cur.getString(1)?.takeIf { it.isNotBlank() }
                    val number = cur.getString(0).orEmpty()
                    out.put(JSONObject()
                        .put("from", name ?: number.ifBlank { "a private number" })
                        .put("inContacts", name != null)
                        .put("type", when (cur.getInt(2)) { CallLog.Calls.MISSED_TYPE -> "missed"; CallLog.Calls.REJECTED_TYPE -> "declined"; else -> "voicemail" })
                        .put("minutesAgo", (now - cur.getLong(3)) / 60_000)
                        .put("unseen", cur.getInt(5) == 1))
                }
            }
        }
        return out
    }
}
