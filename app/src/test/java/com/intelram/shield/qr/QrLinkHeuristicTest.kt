package com.intelram.shield.qr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QrLinkHeuristicTest {

    @Test
    fun `plain text payload is not treated as a link`() {
        val result = QrLinkHeuristic.evaluate("just some text, not a URL")
        assertTrue(result is LinkSafety.PlainText)
    }

    @Test
    fun `wifi config payload is not treated as a link`() {
        val result = QrLinkHeuristic.evaluate("WIFI:S:MyNetwork;T:WPA;P:password123;;")
        assertTrue(result is LinkSafety.PlainText)
    }

    @Test
    fun `clean https URL with a normal domain is safe`() {
        val result = QrLinkHeuristic.evaluate("https://www.wikipedia.org/wiki/QR_code")
        assertTrue(result is LinkSafety.Safe)
        assertEquals("www.wikipedia.org", (result as LinkSafety.Safe).host)
    }

    @Test
    fun `http scheme is flagged as unencrypted`() {
        val result = QrLinkHeuristic.evaluate("http://example.com/page")
        assertTrue(result is LinkSafety.Suspicious)
        val reasons = (result as LinkSafety.Suspicious).reasons
        assertTrue(reasons.any { it.contains("unencrypted", ignoreCase = true) })
    }

    @Test
    fun `raw IP host is flagged`() {
        val result = QrLinkHeuristic.evaluate("http://192.168.1.50/login")
        assertTrue(result is LinkSafety.Suspicious)
        val reasons = (result as LinkSafety.Suspicious).reasons
        assertTrue(reasons.any { it.contains("IP address", ignoreCase = true) })
    }

    @Test
    fun `punycode host is flagged`() {
        val result = QrLinkHeuristic.evaluate("https://xn--pple-43d.com/signin")
        assertTrue(result is LinkSafety.Suspicious)
        val reasons = (result as LinkSafety.Suspicious).reasons
        assertTrue(reasons.any { it.contains("punycode", ignoreCase = true) })
    }

    @Test
    fun `known link shortener is flagged`() {
        val result = QrLinkHeuristic.evaluate("https://bit.ly/3xample")
        assertTrue(result is LinkSafety.Suspicious)
        val reasons = (result as LinkSafety.Suspicious).reasons
        assertTrue(reasons.any { it.contains("shortener", ignoreCase = true) })
    }

    @Test
    fun `risky TLD is flagged`() {
        val result = QrLinkHeuristic.evaluate("https://free-prize.xyz/claim")
        assertTrue(result is LinkSafety.Suspicious)
        val reasons = (result as LinkSafety.Suspicious).reasons
        assertTrue(reasons.any { it.contains(".xyz") })
    }

    @Test
    fun `userinfo at-sign trick is flagged`() {
        val result = QrLinkHeuristic.evaluate("https://trusted-bank.com@evil-site.example/phish")
        assertTrue(result is LinkSafety.Suspicious)
        val reasons = (result as LinkSafety.Suspicious).reasons
        assertTrue(reasons.any { it.contains("@") })
    }

    @Test
    fun `excessive subdomains are flagged`() {
        val result = QrLinkHeuristic.evaluate("https://secure.login.account.verify.example.com/")
        assertTrue(result is LinkSafety.Suspicious)
        val reasons = (result as LinkSafety.Suspicious).reasons
        assertTrue(reasons.any { it.contains("subdomains", ignoreCase = true) })
    }

    @Test
    fun `a link can trigger multiple red flags at once`() {
        // http (unencrypted) + punycode + a risky TLD, all in one URL.
        val result = QrLinkHeuristic.evaluate("http://xn--e1aybc.tk/urgent-claim")
        assertTrue(result is LinkSafety.Suspicious)
        val reasons = (result as LinkSafety.Suspicious).reasons
        assertTrue("expected at least 3 reasons, got: $reasons", reasons.size >= 3)
    }
}
