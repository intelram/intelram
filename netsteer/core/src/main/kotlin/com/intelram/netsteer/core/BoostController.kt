package com.intelram.netsteer.core

/**
 * Battery-aware use of mobile data ("smart combining").
 *
 * The mobile radio is the expensive part: every short connection wakes it and
 * keeps it in a high-power state for several seconds afterwards. So while
 * Wi-Fi is up and traffic is light, everything stays on Wi-Fi and the mobile
 * network is not even requested. When sustained heavy traffic shows up
 * (a download, an update, a video loading), mobile is requested and new
 * connections are spread across both. When traffic calms down, mobile stops
 * receiving new connections, and the request is released after a linger period.
 *
 * Pure logic; [update] is called about once a second with the measured rate.
 */
class BoostController(
    private val onBps: Double = 2_000_000.0,
    private val onSustainMs: Long = 3_000,
    private val offBps: Double = 500_000.0,
    private val offSustainMs: Long = 30_000,
    private val lingerMs: Long = 60_000,
) {
    data class Decision(
        /** Hold a requestNetwork(CELLULAR) so mobile stays connected while Wi-Fi is up. */
        val requestMobile: Boolean,
        /** Offer mobile to new connections. */
        val mobileForNewConnections: Boolean,
        val boosting: Boolean,
    )

    private var boosting = false
    private var aboveSince = -1L
    private var belowSince = -1L
    private var boostEndedAt = Long.MIN_VALUE / 2

    fun update(nowMs: Long, totalBps: Double, wifiUp: Boolean, combine: Boolean, smart: Boolean): Decision {
        if (!combine) {
            reset()
            // Combining off: mobile only when there is no Wi-Fi (then it's the system default anyway).
            return Decision(requestMobile = false, mobileForNewConnections = !wifiUp, boosting = false)
        }
        if (!smart) {
            reset()
            return Decision(requestMobile = true, mobileForNewConnections = true, boosting = false)
        }
        if (!wifiUp) {
            // Nothing to combine with; mobile carries everything as the default network.
            reset()
            return Decision(requestMobile = false, mobileForNewConnections = true, boosting = false)
        }

        if (!boosting) {
            if (totalBps >= onBps) {
                if (aboveSince < 0) aboveSince = nowMs
                if (nowMs - aboveSince >= onSustainMs) {
                    boosting = true
                    belowSince = -1
                }
            } else {
                aboveSince = -1
            }
        } else {
            if (totalBps < offBps) {
                if (belowSince < 0) belowSince = nowMs
                if (nowMs - belowSince >= offSustainMs) {
                    boosting = false
                    aboveSince = -1
                    boostEndedAt = nowMs
                }
            } else {
                belowSince = -1
            }
        }
        val lingering = !boosting && nowMs - boostEndedAt < lingerMs
        return Decision(requestMobile = boosting || lingering, mobileForNewConnections = boosting, boosting = boosting)
    }

    private fun reset() {
        boosting = false
        aboveSince = -1
        belowSince = -1
    }
}
