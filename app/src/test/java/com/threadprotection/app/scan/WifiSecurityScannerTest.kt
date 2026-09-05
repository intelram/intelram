package com.threadprotection.app.scan

import com.threadprotection.app.data.Category
import com.threadprotection.app.ui.theme.Severity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Wi-Fi assessment's judgement calls, tested without a phone or a nearby access point.
 *
 * Capability strings below are the real format Android reports in `ScanResult.capabilities`, which
 * is the only way to read a network's cipher before Android 12 — so the parsing has to be right for
 * the majority of devices in use, and can't be checked by running the app on one modern handset.
 */
class WifiSecurityScannerTest {

    private fun enc(caps: String) = WifiSecurityScanner.encryptionFromCapabilities(caps)

    @Test
    fun `capability strings map to the right cipher`() {
        assertEquals(WifiSecurityScanner.Encryption.WPA2, enc("[WPA2-PSK-CCMP][ESS]"))
        assertEquals(WifiSecurityScanner.Encryption.WPA2, enc("[RSN-PSK-CCMP][ESS]"))
        assertEquals(WifiSecurityScanner.Encryption.WPA3, enc("[RSN-SAE-CCMP][ESS]"))
        assertEquals(WifiSecurityScanner.Encryption.WEP, enc("[WEP][ESS]"))
        assertEquals(WifiSecurityScanner.Encryption.WPA, enc("[WPA-PSK-TKIP][ESS]"))
        assertEquals(WifiSecurityScanner.Encryption.ENTERPRISE, enc("[WPA2-EAP-CCMP][ESS]"))
        assertEquals(WifiSecurityScanner.Encryption.OWE, enc("[RSN-OWE-CCMP][ESS]"))
    }

    /** An access point with no cipher token at all is an open network — the case that matters most. */
    @Test
    fun `no cipher token means an open network`() {
        assertEquals(WifiSecurityScanner.Encryption.OPEN, enc("[ESS]"))
        assertEquals(WifiSecurityScanner.Encryption.OPEN, enc(""))
    }

    /** WPA3 must win over the WPA2/RSN token that appears alongside it in transition mode. */
    @Test
    fun `mixed-mode access points report the strongest cipher offered`() {
        assertEquals(WifiSecurityScanner.Encryption.WPA3, enc("[WPA2-PSK-CCMP][RSN-SAE-CCMP][ESS]"))
    }

    private fun net(
        encryption: WifiSecurityScanner.Encryption = WifiSecurityScanner.Encryption.WPA2,
        onWifi: Boolean = true,
        dnsHijack: Boolean? = false,
        captive: Boolean = false,
    ) = WifiSecurityScanner.NetworkSecurity(
        onWifi = onWifi,
        ssid = "Test Net",
        encryption = encryption,
        captivePortal = captive,
        validated = true,
        vpnActive = false,
        privateDns = null,
        dnsServers = listOf("1.1.1.1"),
        dnsHijack = dnsHijack,
    )

    @Test
    fun `a properly secured network raises nothing`() {
        assertTrue(WifiSecurityScanner.findings(net()).isEmpty())
        assertTrue(WifiSecurityScanner.findings(net(WifiSecurityScanner.Encryption.WPA3)).isEmpty())
        assertTrue(WifiSecurityScanner.findings(net(WifiSecurityScanner.Encryption.ENTERPRISE)).isEmpty())
    }

    /** Android refusing to say is not evidence of a problem — it must never invent one. */
    @Test
    fun `an unreadable cipher raises nothing rather than guessing`() {
        assertTrue(WifiSecurityScanner.findings(net(WifiSecurityScanner.Encryption.UNKNOWN)).isEmpty())
    }

    @Test
    fun `nothing is reported when the phone is not on Wi-Fi at all`() {
        val off = net(WifiSecurityScanner.Encryption.OPEN, onWifi = false, dnsHijack = true, captive = true)
        assertTrue(WifiSecurityScanner.findings(off).isEmpty())
    }

    @Test
    fun `an open network is flagged`() {
        val findings = WifiSecurityScanner.findings(net(WifiSecurityScanner.Encryption.OPEN))
        assertEquals(1, findings.size)
        assertEquals(Category.NETWORK, findings[0].cat)
        assertEquals(Severity.HIGH, findings[0].sev)
        assertTrue(findings[0].name.contains("Test Net"))
    }

    @Test
    fun `WEP is treated as more serious than an open network`() {
        val wep = WifiSecurityScanner.findings(net(WifiSecurityScanner.Encryption.WEP)).single()
        val open = WifiSecurityScanner.findings(net(WifiSecurityScanner.Encryption.OPEN)).single()
        assertEquals(Severity.CRITICAL, wep.sev)
        assertTrue("WEP should outrank an open network", wep.risk > open.risk)
    }

    @Test
    fun `DNS tampering is reported on its own`() {
        val findings = WifiSecurityScanner.findings(net(dnsHijack = true))
        assertEquals(1, findings.size)
        assertEquals("wifi-dns-hijack", findings[0].id)
    }

    /** An untestable probe must not be read as a clean result or as a problem. */
    @Test
    fun `an untestable DNS probe reports nothing`() {
        assertTrue(WifiSecurityScanner.findings(net(dnsHijack = null)).isEmpty())
    }

    @Test
    fun `separate problems each produce their own finding`() {
        val findings = WifiSecurityScanner.findings(
            net(WifiSecurityScanner.Encryption.OPEN, dnsHijack = true, captive = true),
        )
        assertEquals(3, findings.size)
        assertEquals(setOf("wifi-open", "wifi-dns-hijack", "wifi-captive"), findings.map { it.id }.toSet())
    }

    @Test
    fun `the summary line names what was actually seen`() {
        assertEquals("Not on Wi-Fi — mobile data or no connection", WifiSecurityScanner.summaryLabel(net(onWifi = false)))
        assertEquals("WPA3", WifiSecurityScanner.summaryLabel(net(WifiSecurityScanner.Encryption.WPA3)))
        assertTrue(WifiSecurityScanner.summaryLabel(net(dnsHijack = true)).contains("DNS tampering"))
    }

    /** Guards the constant mapping against Android's SECURITY_TYPE_* values. */
    @Test
    fun `android security type constants map correctly`() {
        assertEquals(WifiSecurityScanner.Encryption.OPEN, WifiSecurityScanner.securityTypeToEncryption(0))
        assertEquals(WifiSecurityScanner.Encryption.WEP, WifiSecurityScanner.securityTypeToEncryption(1))
        assertEquals(WifiSecurityScanner.Encryption.WPA2, WifiSecurityScanner.securityTypeToEncryption(2))
        assertEquals(WifiSecurityScanner.Encryption.WPA3, WifiSecurityScanner.securityTypeToEncryption(4))
        assertEquals(WifiSecurityScanner.Encryption.OWE, WifiSecurityScanner.securityTypeToEncryption(6))
    }
}
