package org.voiceassistant

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.ContactsContract
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

/**
 * Reads incoming texts (SMS and RCS) from the messaging app's notifications, so chat features work
 * too, and hands the ones from saved CONTACTS to the assistant to read aloud. Unknown numbers, short codes
 * and spam are dropped silently. Replies go back through the notification's own reply action, the
 * same path as replying from the shade.
 */
class MessageListener : NotificationListenerService() {
    companion object {
        private const val TAG = "AssistantTexts"
        private val APPS = setOf("com.google.android.apps.messaging", "com.samsung.android.messaging")

        /** [missed]: arrived while the assistant was silent (sleep mode / quiet hours); the morning briefing reads [text]. */
        data class Thread(val key: String, val from: String, val reply: Notification.Action, val at: Long = System.currentTimeMillis(),
                          val text: String = "", var missed: Boolean = false)
        /** Conversations from contacts that the assistant can reply to, newest last. Kept for a day. */
        private val threads = LinkedHashMap<String, Thread>()
        private val lastSeen = HashMap<String, String>()
        private const val KEEP_MS = 24 * 3600_000L

        fun thread(key: String) = synchronized(threads) { threads[key] }
        fun markMissed(key: String) = synchronized(threads) { threads[key]?.missed = true }
        /** After the briefing has read them out. */
        fun clearMissed() = synchronized(threads) { threads.values.forEach { it.missed = false } }

        /** Recent conversations, newest first: what the brain is told the user can reply to. */
        fun recent(): List<Thread> = synchronized(threads) {
            val cutoff = System.currentTimeMillis() - KEEP_MS
            threads.values.filter { it.at > cutoff }.sortedByDescending { it.at }
        }

        /** The newest recent conversation whose sender matches [name] (case-insensitive, whole or first name). */
        fun findByName(name: String): Thread? {
            val n = name.trim().lowercase()
            if (n.isEmpty()) return null
            val r = recent()
            return r.firstOrNull { it.from.lowercase() == n }
                ?: r.firstOrNull { it.from.lowercase().split(" ").firstOrNull() == n }
                ?: r.firstOrNull { it.from.lowercase().contains(n) }
        }

        /** Sends [text] through the conversation's reply action. Returns null on success, else why not. */
        fun reply(ctx: Context, key: String, text: String): String? {
            val t = thread(key) ?: return "that conversation isn't open any more"
            val inputs = t.reply.remoteInputs ?: return "no reply box on that notification"
            val intent = Intent()
            val results = Bundle().apply { inputs.forEach { putCharSequence(it.resultKey, text) } }
            RemoteInput.addResultsToIntent(inputs, intent, results)
            return runCatching { t.reply.actionIntent.send(ctx, 0, intent); null }
                .getOrElse { if (it is PendingIntent.CanceledException) "that conversation was closed" else it.message ?: "send failed" }
        }

        fun isContact(ctx: Context, name: String?, people: List<String>): Boolean {
            if (ctx.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return false
            val cr = ctx.contentResolver
            // 1) a sender Person pointing at a contact or a phone number we can look up
            for (uri in people) {
                if (uri.startsWith("content://com.android.contacts")) return true
                if (uri.startsWith("tel:")) {
                    val lookup = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(uri.removePrefix("tel:")))
                    runCatching { cr.query(lookup, arrayOf(ContactsContract.PhoneLookup._ID), null, null, null)?.use { if (it.count > 0) return true } }
                }
            }
            // 2) the display name the messaging app shows (it shows a bare number for strangers)
            if (name.isNullOrBlank() || name.any { it.isDigit() } && name.count { it.isDigit() } >= 5) return false
            return runCatching {
                cr.query(ContactsContract.Contacts.CONTENT_URI, arrayOf(ContactsContract.Contacts._ID),
                    "${ContactsContract.Contacts.DISPLAY_NAME_PRIMARY} = ?", arrayOf(name), null)?.use { it.count > 0 } ?: false
            }.getOrDefault(false)
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName !in APPS) return
        val n = sbn.notification
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        val ex = n.extras
        // Latest message in a MessagingStyle notification; fall back to title/text.
        val msgs = ex.getParcelableArray(Notification.EXTRA_MESSAGES, android.os.Parcelable::class.java)
        val last = msgs?.lastOrNull() as? Bundle
        val sender = last?.getCharSequence("sender")?.toString()
            ?: (last?.getParcelable("sender_person", android.app.Person::class.java))?.name?.toString()
            ?: ex.getCharSequence(Notification.EXTRA_TITLE)?.toString()
        val body = last?.getCharSequence("text")?.toString() ?: ex.getCharSequence(Notification.EXTRA_TEXT)?.toString()
        if (sender.isNullOrBlank() || body.isNullOrBlank()) return
        // The user's own replies show up as the newest message too: no sender, or the notification's own "me" person.
        val me = ex.getParcelable(Notification.EXTRA_MESSAGING_PERSON, android.app.Person::class.java)?.name?.toString()
            ?: ex.getCharSequence(Notification.EXTRA_SELF_DISPLAY_NAME)?.toString()
        if (last != null && last.getCharSequence("sender") == null && last.getParcelable("sender_person", android.app.Person::class.java) == null) return
        if (me != null && sender == me) return

        synchronized(threads) {
            if (lastSeen[sbn.key] == body) return   // same message re-posted (read receipts, updates)
            lastSeen[sbn.key] = body
        }
        val people = buildList {
            ex.getParcelableArrayList(Notification.EXTRA_PEOPLE_LIST, android.app.Person::class.java)?.forEach { p -> p.uri?.let { add(it) } }
            (last?.getParcelable("sender_person", android.app.Person::class.java))?.uri?.let { add(it) }
        }
        if (!isContact(this, sender, people)) { Log.i(TAG, "skipped a text from a non-contact"); return }

        val replyAction = n.actions?.firstOrNull { a -> a.remoteInputs?.any { it.allowFreeFormInput } == true }
        if (replyAction != null) synchronized(threads) {
            threads.remove(sbn.key); threads[sbn.key] = Thread(sbn.key, sender, replyAction, text = body.toString().take(500))
            while (threads.size > 30) threads.remove(threads.keys.first())
        }
        Log.i(TAG, "text from a contact (${body.length} chars), reply=${replyAction != null}")
        AssistantService.announceText(this, sbn.key, sender, body)
    }
}
