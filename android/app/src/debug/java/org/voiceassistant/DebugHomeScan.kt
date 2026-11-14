package org.voiceassistant

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlin.concurrent.thread

/** Debug builds only: run a Wi-Fi scan from adb and log the result (works with the phone locked).
 *  adb shell am broadcast -n org.voiceassistant/.DebugHomeScan */
class DebugHomeScan : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        val pending = goAsync()
        thread {
            try { HomeScan.scan(c).forEach { Log.i("Assistant", "  ${it.support} | ${it.brand} | ${it.kind} | ${it.name} | ${it.ip} | ${it.via}") } }
            finally { pending.finish() }
        }
    }
}
