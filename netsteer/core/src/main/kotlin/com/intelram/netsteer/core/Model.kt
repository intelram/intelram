package com.intelram.netsteer.core

/**
 * Platform-independent view of one Android network. The app layer maps
 * android.net.Network / NetworkCapabilities into this so that the policy
 * and diagnosis logic can be unit-tested on a plain JVM.
 */
enum class Transport { WIFI, CELLULAR, ETHERNET, VPN, OTHER }

data class ProbeStats(
    /** Median round-trip of recent TCP-connect probes, null if all failed. */
    val rttMs: Int?,
    /** Fraction of recent probes that failed, 0.0..1.0. */
    val lossRatio: Double,
    val sampledAtMs: Long,
)

data class NetworkSnapshot(
    /** android.net.Network#getNetworkHandle(); stable for the network's lifetime. */
    val id: Long,
    val transport: Transport,
    /** NET_CAPABILITY_VALIDATED: the OS reached its connectivity-check server. */
    val validated: Boolean,
    /** NET_CAPABILITY_CAPTIVE_PORTAL. */
    val captivePortal: Boolean,
    /** Absence of NET_CAPABILITY_NOT_METERED. */
    val metered: Boolean,
    /** NetworkCallback#onBlockedStatusChanged (data saver, background restriction). */
    val blocked: Boolean,
    /** OS link-bandwidth estimate; coarse, often a static per-RAT value on cellular. */
    val downKbps: Int,
    val upKbps: Int,
    /** Our own active probe results, null until the first probe completes. */
    val probe: ProbeStats? = null,
)

