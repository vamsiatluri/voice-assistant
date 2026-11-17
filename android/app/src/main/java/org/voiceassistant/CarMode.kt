package org.voiceassistant

import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log

/**
 * Car mode: when the phone connects to the car's Bluetooth, the assistant goes hands-free. Texts are read
 * at any hour and the user's Claude threads are announced (the brain treats it as "heading out"); when the
 * car disconnects it all goes back to normal.
 *
 * Which device is the car: one the user named ("this is my car" while connected), else anything that
 * reports itself as car audio or a hands-free kit. Earbuds and speakers don't count.
 */
object CarMode {
    private const val TAG = "Assistant"
    private fun p(c: Context) = c.getSharedPreferences("assistant", Context.MODE_PRIVATE)

    fun inCar(c: Context) = p(c).getBoolean("inCar", false)
    private fun carAddress(c: Context) = p(c).getString("carAddress", null)
    private fun lastConnected(c: Context) = p(c).getString("lastBtAddress", null)

    @android.annotation.SuppressLint("MissingPermission")   // BLUETOOTH_CONNECT may be denied: runCatching returns false
    private fun looksLikeCar(d: BluetoothDevice): Boolean = runCatching {
        val k = d.bluetoothClass?.deviceClass
        k == BluetoothClass.Device.AUDIO_VIDEO_CAR_AUDIO || k == BluetoothClass.Device.AUDIO_VIDEO_HANDSFREE
    }.getOrDefault(false)

    private fun isCar(c: Context, d: BluetoothDevice): Boolean {
        val saved = carAddress(c)
        return if (saved != null) d.address == saved else looksLikeCar(d)
    }

    /** Voice: "car mode on/off", or "this is my car" (remember the device connected right now). */
    fun command(c: Context, action: String): String? {
        when (action) {
            "remember" -> {
                val a = lastConnected(c) ?: return "no Bluetooth device has connected since ${Persona.NAME} started"
                p(c).edit().putString("carAddress", a).apply()
                set(c, true)
            }
            "forget" -> p(c).edit().remove("carAddress").apply()
            "on" -> set(c, true)
            "off" -> set(c, false)
        }
        return null
    }

    /** onChange(on) is called on a real change only. */
    fun receiver(onChange: (Boolean) -> Unit) = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            @Suppress("DEPRECATION")   // the typed overload is API 33; minSdk is 31
            val d: BluetoothDevice = i.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE) ?: return
            when (i.action) {
                BluetoothDevice.ACTION_ACL_CONNECTED -> {
                    p(c).edit().putString("lastBtAddress", d.address).apply()
                    if (isCar(c, d) && !inCar(c)) { set(c, true); onChange(true) }
                }
                BluetoothDevice.ACTION_ACL_DISCONNECTED ->
                    if (inCar(c) && isCar(c, d)) { set(c, false); onChange(false) }
            }
        }
    }

    val filter get() = IntentFilter().apply {
        addAction(BluetoothDevice.ACTION_ACL_CONNECTED); addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
    }

    private fun set(c: Context, on: Boolean) {
        p(c).edit().putBoolean("inCar", on).apply()
        Log.i(TAG, "car mode ${if (on) "on" else "off"}")
    }
}
