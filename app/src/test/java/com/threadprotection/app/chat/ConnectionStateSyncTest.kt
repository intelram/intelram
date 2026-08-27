package com.threadprotection.app.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Test cases for accepting connection requests and for the state-synchronisation defect where the
 * sender saw "Connected" while the recipient saw "Connection Failed".
 *
 * The root cause was that scanning and connecting shared one state value, so a scan event on the
 * recipient (whose Chat screen keeps a continuous BLE scan running) could overwrite a healthy
 * connection state. These tests pin the rule that fixed it.
 */
class ConnectionStateSyncTest {

    // ── a connection owns the radio; discovery must not talk over it ────────────────────────

    @Test
    fun `every connection-active state owns the radio`() {
        val active = listOf(
            BtChatConnState.CONNECTING,
            BtChatConnState.HANDSHAKING,
            BtChatConnState.REQUEST_SENT,
            BtChatConnState.REQUEST_RECEIVED,
            BtChatConnState.CONNECTED,
        )
        active.forEach { assertTrue("$it should own the radio", ChatStateRules.ownsRadio(it)) }
    }

    @Test
    fun `idle and finished states do not own the radio`() {
        val free = listOf(
            BtChatConnState.IDLE,
            BtChatConnState.DISCOVERING,
            BtChatConnState.SCAN_FAILED,
            BtChatConnState.FAILED,
            BtChatConnState.DENIED,
            BtChatConnState.REQUEST_TIMEOUT,
            BtChatConnState.BT_UNAVAILABLE,
            BtChatConnState.NO_PERMISSION,
            BtChatConnState.BLE_UNSUPPORTED,
        )
        free.forEach { assertFalse("$it should not own the radio", ChatStateRules.ownsRadio(it)) }
    }

    /** The exact defect: a scan failing on the recipient must not be shown as a failed connection. */
    @Test
    fun `a scan failure cannot overwrite a live connection`() {
        assertFalse(ChatStateRules.mayPublishScanState(BtChatConnState.CONNECTED))
        assertFalse(ChatStateRules.mayPublishScanState(BtChatConnState.REQUEST_RECEIVED))
        assertFalse(ChatStateRules.mayPublishScanState(BtChatConnState.HANDSHAKING))
    }

    @Test
    fun `discovery may publish freely when nothing is connected`() {
        assertTrue(ChatStateRules.mayPublishScanState(BtChatConnState.IDLE))
        assertTrue(ChatStateRules.mayPublishScanState(BtChatConnState.FAILED))
        assertTrue(ChatStateRules.mayPublishScanState(BtChatConnState.DISCOVERING))
    }

    // ── accepting a connection request ──────────────────────────────────────────────────────

    @Test
    fun `messages are refused until the request has actually been accepted`() {
        // A socket being open is not agreement to chat: REQUEST_RECEIVED has a live, encrypted
        // socket and still must not carry text.
        assertFalse(ChatStateRules.canSendText(BtChatConnState.REQUEST_RECEIVED, hasSocket = true, hasSessionKey = true))
        assertFalse(ChatStateRules.canSendText(BtChatConnState.REQUEST_SENT, hasSocket = true, hasSessionKey = true))
        assertFalse(ChatStateRules.canSendText(BtChatConnState.HANDSHAKING, hasSocket = true, hasSessionKey = true))
    }

    @Test
    fun `messages are allowed once accepted on a live socket`() {
        assertTrue(ChatStateRules.canSendText(BtChatConnState.CONNECTED, hasSocket = true, hasSessionKey = true))
    }

    /** The "connecting — not in range" report: state said connected, the socket had gone. */
    @Test
    fun `a connected state with no socket still refuses to send`() {
        assertFalse(ChatStateRules.canSendText(BtChatConnState.CONNECTED, hasSocket = false, hasSessionKey = true))
        assertFalse(ChatStateRules.canSendText(BtChatConnState.CONNECTED, hasSocket = true, hasSessionKey = false))
    }

    // ── reconnecting after a failure ────────────────────────────────────────────────────────

    @Test
    fun `states the user must read survive an ordinary socket close`() {
        assertTrue(ChatStateRules.isTerminalExplanation(BtChatConnState.DENIED))
        assertTrue(ChatStateRules.isTerminalExplanation(BtChatConnState.REQUEST_TIMEOUT))
    }

    @Test
    fun `an ordinary end of session is not a terminal explanation`() {
        assertFalse(ChatStateRules.isTerminalExplanation(BtChatConnState.CONNECTED))
        assertFalse(ChatStateRules.isTerminalExplanation(BtChatConnState.IDLE))
    }

    @Test
    fun `a failed attempt leaves the radio free so discovery and a retry can proceed`() {
        assertFalse(ChatStateRules.ownsRadio(BtChatConnState.FAILED))
        assertTrue(ChatStateRules.mayPublishScanState(BtChatConnState.FAILED))
    }

    // ── pending-state bookkeeping used by the UI ────────────────────────────────────────────

    @Test
    fun `pending covers exactly the two request states`() {
        assertTrue(BtChatConnState.REQUEST_SENT.isPending)
        assertTrue(BtChatConnState.REQUEST_RECEIVED.isPending)
        assertFalse(BtChatConnState.CONNECTED.isPending)
        assertFalse(BtChatConnState.IDLE.isPending)
    }

    @Test
    fun `the accepted path walks a state sequence both phones agree on`() {
        // Initiator: CONNECTING → HANDSHAKING → REQUEST_SENT → CONNECTED.
        // Responder:              HANDSHAKING → REQUEST_RECEIVED → CONNECTED.
        // Every step before CONNECTED owns the radio, so no scan event can interrupt either side.
        val initiator = listOf(
            BtChatConnState.CONNECTING,
            BtChatConnState.HANDSHAKING,
            BtChatConnState.REQUEST_SENT,
            BtChatConnState.CONNECTED,
        )
        val responder = listOf(
            BtChatConnState.HANDSHAKING,
            BtChatConnState.REQUEST_RECEIVED,
            BtChatConnState.CONNECTED,
        )
        (initiator + responder).forEach { assertTrue("$it must be protected", ChatStateRules.ownsRadio(it)) }
        assertEquals(BtChatConnState.CONNECTED, initiator.last())
        assertEquals(BtChatConnState.CONNECTED, responder.last())
    }
}
