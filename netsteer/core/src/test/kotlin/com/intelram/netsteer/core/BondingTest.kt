package com.intelram.netsteer.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BondingTest {
    @Test fun securityFromRealCapabilityStrings() {
        assertEquals(WifiSecurity.WPA2_PSK, WifiSecurity.parse("[WPA2-PSK-CCMP][RSN-PSK-CCMP][ESS][WPS]"))
        assertEquals(WifiSecurity.WPA3_SAE, WifiSecurity.parse("[RSN-SAE-CCMP][ESS][MFPR][MFPC]"))
        assertEquals(WifiSecurity.WPA2_WPA3, WifiSecurity.parse("[RSN-PSK+SAE-CCMP][ESS][MFPC]"))
        assertEquals(WifiSecurity.ENTERPRISE, WifiSecurity.parse("[WPA2-EAP/SHA1-CCMP][RSN-EAP/SHA1-CCMP][ESS]"))
        assertEquals(WifiSecurity.OWE, WifiSecurity.parse("[RSN-OWE-CCMP][ESS][MFPR][MFPC]"))
        assertEquals(WifiSecurity.WEP, WifiSecurity.parse("[WEP][ESS]"))
        assertEquals(WifiSecurity.OPEN, WifiSecurity.parse("[ESS]"))
        assertEquals(WifiSecurity.OPEN, WifiSecurity.parse(null))
    }

    @Test fun oneToFourAddedFifthRejected() {
        val s = WifiSelection()
        for (i in 1..4) {
            assertEquals(WifiSelection.AddResult.ADDED, s.add("Net$i", WifiSecurity.WPA2_PSK))
            assertEquals(i, s.size)
        }
        assertTrue(s.isFull)
        assertEquals(WifiSelection.AddResult.FULL, s.add("Net5", WifiSecurity.OPEN))
        assertEquals(4, s.size)
        assertTrue(s.remove("Net2", WifiSecurity.WPA2_PSK))
        assertEquals(WifiSelection.AddResult.ADDED, s.add("Net5", WifiSecurity.OPEN))
    }

    @Test fun duplicatesUnsupportedAndInvalidRejected() {
        val s = WifiSelection()
        assertEquals(WifiSelection.AddResult.ADDED, s.add("Home", WifiSecurity.WPA2_PSK))
        assertEquals(WifiSelection.AddResult.DUPLICATE, s.add(" Home ", WifiSecurity.WPA2_PSK))
        assertEquals(WifiSelection.AddResult.UNSUPPORTED, s.add("Corp", WifiSecurity.ENTERPRISE))
        assertEquals(WifiSelection.AddResult.UNSUPPORTED, s.add("Old", WifiSecurity.WEP))
        assertEquals(WifiSelection.AddResult.INVALID_SSID, s.add("", WifiSecurity.OPEN))
        assertEquals(WifiSelection.AddResult.INVALID_SSID, s.add("x".repeat(33), WifiSecurity.OPEN))
        assertEquals(1, s.size)
    }

    @Test fun selectionRoundTripsWithoutPasswords() {
        val s = WifiSelection()
        s.add("Café Wi-Fi", WifiSecurity.WPA3_SAE)
        s.add("Guest", WifiSecurity.OPEN)
        val text = s.serialize()
        assertFalse(text.contains("pass", ignoreCase = true))
        assertEquals(s.list, WifiSelection.deserialize(text).list)
        assertEquals(0, WifiSelection.deserialize("garbage\nNOPE\tx").size)
    }

    @Test fun bandsAndBars() {
        assertEquals("2.4 GHz", WifiBands.bandOf(2437))
        assertEquals("5 GHz", WifiBands.bandOf(5180))
        assertEquals("6 GHz", WifiBands.bandOf(5975))
        assertEquals(4, WifiBands.bars(-50))
        assertEquals(0, WifiBands.bars(-95))
    }

    @Test fun modeIsNeverReportedAsBonding() {
        assertEquals(ConnectionMode.OFF, ConnectionMode.classify(false, 3, 0))
        assertEquals(ConnectionMode.NO_NETWORK, ConnectionMode.classify(true, 0, 2))
        assertEquals(ConnectionMode.SINGLE, ConnectionMode.classify(true, 1, 0))
        assertEquals(ConnectionMode.FAILOVER, ConnectionMode.classify(true, 1, 3))
        assertEquals(ConnectionMode.LOAD_BALANCING, ConnectionMode.classify(true, 2, 0))
        assertTrue(ConnectionMode.entries.none { it.label.contains("bond", ignoreCase = true) })
    }

    @Test fun healthMarksMostlyFailingLinksAndExpires() {
        var now = 0L
        val h = HealthBook(clock = { now }, freshMs = 60_000)
        h.record("wifi", 30, 0.0)
        h.record("cell", null, 1.0)
        assertFalse(h.unhealthy("wifi"))
        assertTrue(h.unhealthy("cell"))
        now += 60_001
        assertFalse(h.unhealthy("cell")) // stale results don't keep a link out forever
        assertNull(h.get("wifi"))
    }
}
