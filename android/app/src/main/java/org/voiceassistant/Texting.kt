package org.voiceassistant

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.telephony.SmsManager
import android.util.Log

/**
 * Sends a text the user dictated, to someone the user named. Only ever called after the assistant read the exact words
 * and recipient back and the user said yes.
 *  1. If that person texted recently, reply in that conversation (keeps RCS chats and groups intact).
 *  2. Otherwise look the name up in the user's contacts and send an SMS to their mobile number.
 */
object Texting {
    private const val TAG = "AssistantTexts"

    /** Returns null on success, otherwise a short spoken reason. */
    fun send(ctx: Context, to: String, text: String, openKey: String?): String? {
        if (text.isBlank()) return "the message was empty"
        // "Reply" with no name = the conversation the user was just read (or the newest one).
        // Never guess "the newest conversation": someone else may have texted since.
        val thread = if (to.isBlank()) openKey?.let { MessageListener.thread(it) } else MessageListener.findByName(to)
        if (thread != null) {
            val why = MessageListener.reply(ctx, thread.key, text)
            if (why == null) { Log.i(TAG, "sent as a reply in the open conversation"); return null }
            Log.w(TAG, "conversation reply failed ($why); falling back to SMS")
            if (to.isBlank()) return sms(ctx, thread.from, text)
        } else if (to.isBlank()) return "I don't have a recent text to reply to. Tell me who it's for"
        return sms(ctx, to, text)
    }

    private fun sms(ctx: Context, name: String, text: String): String? {
        if (ctx.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return "I can't read your contacts"
        if (ctx.checkSelfPermission(Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) return "I don't have permission to send texts yet. Open the ${Persona.NAME} app once and allow it"
        val people = lookup(ctx, name)
        if (people.isEmpty()) return "I couldn't find $name in your contacts"
        val exact = people.filter { it.first.equals(name.trim(), ignoreCase = true) }
        val pick = when {
            exact.size == 1 -> exact[0]
            people.map { it.first }.distinct().size == 1 -> people[0]
            else -> return "you have more than one ${name.trim()}: ${people.map { it.first }.distinct().take(3).joinToString(" or ")}. Say the full name"
        }
        return runCatching {
            val sm = ctx.getSystemService(SmsManager::class.java)
            val parts = sm.divideMessage(text)
            if (parts.size > 1) sm.sendMultipartTextMessage(pick.second, null, parts, null, null)
            else sm.sendTextMessage(pick.second, null, text, null, null)
            Log.i(TAG, "sent an SMS to a contact (${text.length} chars)")
            null
        }.getOrElse { "the phone wouldn't send it: ${it.message}" }
    }

    /** Contacts whose name matches, as (display name, best number): mobile first. */
    private fun lookup(ctx: Context, name: String): List<Pair<String, String>> {
        val q = name.trim()
        if (q.isEmpty()) return emptyList()
        val best = LinkedHashMap<String, Pair<String, Int>>()   // contactId -> (number, rank)
        val names = HashMap<String, String>()
        ctx.contentResolver.query(Phone.CONTENT_URI, arrayOf(Phone.CONTACT_ID, Phone.DISPLAY_NAME_PRIMARY, Phone.NUMBER, Phone.TYPE, Phone.IS_SUPER_PRIMARY),
            "${Phone.DISPLAY_NAME_PRIMARY} LIKE ?", arrayOf("%$q%"), null)?.use { c ->
            while (c.moveToNext()) {
                val id = c.getString(0); val dn = c.getString(1) ?: continue; val num = c.getString(2) ?: continue
                // Whole-word match only: "Al" must not pick "Alice" or "Sal".
                if (dn.lowercase().split(Regex("\\s+")).none { it == q.lowercase() } && !dn.equals(q, true) && !dn.lowercase().startsWith(q.lowercase() + " ")) continue
                val rank = when { c.getInt(4) != 0 -> 0; c.getInt(3) == Phone.TYPE_MOBILE -> 1; else -> 2 }
                names[id] = dn
                if ((best[id]?.second ?: 9) > rank) best[id] = num to rank
            }
        }
        return best.map { (id, v) -> names.getValue(id) to v.first }
    }
}
