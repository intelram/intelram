package com.threadprotection.app.chat

import kotlin.math.pow
import kotlin.math.roundToInt

/** Broad category from the peer's real Bluetooth Class of Device (`BluetoothClass.Device.Major`)
 *  — read straight off the OS during discovery, not guessed from the name. */
enum class BtDeviceKind { PHONE, COMPUTER, AUDIO, WEARABLE, GENERIC }

/**
 * Turns a real RSSI reading (from `BluetoothDevice.EXTRA_RSSI` on `ACTION_FOUND`) into the kind of
 * "how close is this" indicator most Bluetooth-scanner apps show. This is a real, standard
 * technique — the log-distance path-loss model — not a fabricated number: signal strength really
 * does fall off roughly logarithmically with distance. It's still an *estimate*, though: walls,
 * device orientation and antenna design all shift the actual reading, so treat it as "roughly this
 * far", never a laser-measured distance. Weak/uncertain signals fall back to a wide range instead
 * of a falsely precise number, same as commercial BLE finder apps do.
 */
object SignalEstimate {
    /** Typical RSSI at 1 metre for a classic Bluetooth radio — the standard reference point the
     *  path-loss formula measures every other distance against. */
    private const val ASSUMED_RSSI_AT_1M = -59.0
    private const val PATH_LOSS_EXPONENT = 2.2

    fun distanceMeters(rssi: Int): Double =
        10.0.pow((ASSUMED_RSSI_AT_1M - rssi) / (10.0 * PATH_LOSS_EXPONENT))

    fun distanceLabel(rssi: Int?): String {
        if (rssi == null) return "—"
        if (rssi >= ASSUMED_RSSI_AT_1M) return "<1m"
        val meters = distanceMeters(rssi)
        return if (meters <= 10) "~${meters.roundToInt().coerceAtLeast(1)}m" else "10–50m"
    }

    /** 0..4 bars for a signal-strength glyph — thresholds match the bucketing phones already use
     *  for Wi-Fi/cellular signal icons. */
    fun bars(rssi: Int?): Int = when {
        rssi == null -> 0
        rssi > -60 -> 4
        rssi > -70 -> 3
        rssi > -80 -> 2
        else -> 1
    }
}
