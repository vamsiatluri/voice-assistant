package org.voiceassistant

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log

/**
 * Amazon plugin, phone half: opens Amazon's own add-to-cart page for one product. Nothing is bought here:
 * the user taps Add to cart and places the order in Amazon themselves.
 *
 * Android blocks most activity starts from a background service, so the page is opened directly when the
 * phone allows it and is always offered as a notification too ("Tap to add ... to your cart").
 */
object Shop {
    private const val TAG = "Assistant"
    private const val CHANNEL = "shopping"
    private const val NOTIF_ID = 41

    /** Only Amazon add-to-cart links: the brain can't make the phone open anything else through this. */
    fun isCartLink(url: String): Boolean = runCatching {
        val u = Uri.parse(url)
        u.scheme == "https" && Regex("""^(www\.)?amazon\.[a-z.]{2,8}$""").matches(u.host.orEmpty()) &&
            u.path == "/gp/aws/cart/add.html"
    }.getOrDefault(false)

    /**
     * Returns null when the page is open or waiting as a notification, else why not. Android silently drops
     * activity starts from a background service (startActivity doesn't even throw), so the page is opened
     * directly only while the app's own screen is showing; otherwise it's a heads-up notification to tap.
     */
    fun openCart(ctx: Context, url: String, title: String): String? {
        if (!isCartLink(url)) return "that wasn't an Amazon cart link"
        val view = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (onScreen && runCatching { ctx.startActivity(view) }.isSuccess) { Log.i(TAG, "amazon cart link opened: $title"); return null }

        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Shopping", NotificationManager.IMPORTANCE_HIGH))
        val tap = PendingIntent.getActivity(ctx, NOTIF_ID, view, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        nm.notify(NOTIF_ID, Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_add)
            .setContentTitle("Add to your Amazon cart")
            .setContentText(title.ifBlank { "Tap to open Amazon" })
            .setStyle(Notification.BigTextStyle().bigText("${title.ifBlank { "Your item" }}\nTap to open Amazon's add-to-cart page. You place the order yourself."))
            .setContentIntent(tap)
            .setAutoCancel(true)
            .build())
        Log.i(TAG, "amazon cart link offered as a notification: $title")
        return null
    }
}
