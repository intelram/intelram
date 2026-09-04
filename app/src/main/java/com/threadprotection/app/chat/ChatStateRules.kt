package com.threadprotection.app.chat

/**
 * The connection/discovery rules that decide what the two phones in a chat are allowed to say
 * about themselves, pulled out of [BluetoothChatManager] so they can be exercised directly by unit
 * tests. The manager calls straight into these — they are the shipping rules, not a copy of them.
 *
 * These exist because scanning state and connection state used to share one flow, which is how the
 * two ends of a chat could disagree: one phone showed a live conversation while the other showed
 * "Connection Failed" for the very same, working link.
 */
object ChatStateRules {

    /**
     * True while a connection attempt, a pending request or a live chat owns the Bluetooth radio.
     * While this holds, nothing on the discovery path may publish state — the radio is not doing
     * what a scan update would be describing.
     */
    fun ownsRadio(state: BtChatConnState): Boolean = when (state) {
        BtChatConnState.CONNECTING,
        BtChatConnState.HANDSHAKING,
        BtChatConnState.REQUEST_SENT,
        BtChatConnState.REQUEST_RECEIVED,
        BtChatConnState.CONNECTED -> true
        else -> false
    }

    /** Whether a state produced by the discovery path may overwrite [current]. */
    fun mayPublishScanState(current: BtChatConnState): Boolean = !ownsRadio(current)

    /**
     * Whether a text message may be transmitted. Only an explicitly accepted, live session
     * qualifies — a socket being open is not the same as both people having agreed to chat.
     */
    fun canSendText(state: BtChatConnState, hasSocket: Boolean, hasSessionKey: Boolean): Boolean =
        state == BtChatConnState.CONNECTED && hasSocket && hasSessionKey

    /** States the user still needs to read, so an ordinary socket close must not overwrite them. */
    fun isTerminalExplanation(state: BtChatConnState): Boolean =
        state == BtChatConnState.DENIED || state == BtChatConnState.REQUEST_TIMEOUT
}

/**
 * How the nearby-devices list is maintained between BLE sightings. Pure list maths, kept here so
 * the "multiple devices / intermittent discovery" behaviour is testable without a radio.
 */
object NearbyDeviceList {

    /**
     * Folds one sighting into the list: updates the existing entry in place (so signal strength
     * keeps refreshing for a device already on screen) rather than appending a duplicate row for
     * the same address.
     */
    fun merge(current: List<BtDeviceInfo>, sighting: BtDeviceInfo): List<BtDeviceInfo> {
        val known = current.any { it.address == sighting.address }
        return if (known) {
            current.map { existing ->
                if (existing.address != sighting.address) {
                    existing
                } else {
                    // Never downgrade a real advertised name back to the placeholder. The name now
                    // arrives with every sighting (see BluetoothChatManager.startAdvertising's
                    // doc), so this mainly guards a sighting that failed to parse for some other
                    // reason from blanking out a name already known to be good.
                    val keepName = if (sighting.name == PLACEHOLDER_NAME && existing.name != PLACEHOLDER_NAME) {
                        existing.name
                    } else {
                        sighting.name
                    }
                    sighting.copy(name = keepName)
                }
            }
        } else {
            current + sighting
        }
    }

    /** Drops devices that have stopped advertising — BLE has no explicit "I'm leaving" signal, so
     *  absence over time is the only honest way to tell that a device went away. */
    fun dropStale(current: List<BtDeviceInfo>, cutoffMs: Long): List<BtDeviceInfo> =
        current.filter { it.lastSeenMs >= cutoffMs }

    /** Display order: strongest signal first, so the phone physically closest to the user is the
     *  one at the top of the list. Ties fall back to name for a stable, non-jittery order. */
    fun forDisplay(current: List<BtDeviceInfo>): List<BtDeviceInfo> =
        current.sortedWith(compareByDescending<BtDeviceInfo> { it.rssi ?: Int.MIN_VALUE }.thenBy { it.name })

    /** Shown when a peer is advertising this app's service UUID but its name hasn't arrived yet. */
    const val PLACEHOLDER_NAME = "Thread Protection user"
}
