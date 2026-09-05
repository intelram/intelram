package com.threadprotection.app.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for the "every website comes back suspicious" bug.
 *
 * Two independent defects produced it, and each has its own section below:
 *
 * 1. **Substring brand matching.** `contains("irs")` matched the CSS `:first-child`,
 *    `contains("ups")` matched `groups`, `contains("chase")` matched `purchase`, and
 *    `contains("microsoft")` matched the `"microsoft edge"` user-agent sniffing in ordinary
 *    JavaScript. Measured against real pages before the fix: `google.com` tripped 2 findings,
 *    `wikipedia.org` 4, `bbc.com` 6 — each severity 3, each enough to force SUSPICIOUS.
 * 2. **A verdict cliff with no way back.** Any single flag of any severity forced SUSPICIOUS
 *    regardless of how much positive evidence existed alongside it.
 *
 * The strings in the content tests are taken from the real markup that caused the false positives,
 * so a regression in either the stripping or the word-boundary rule fails here rather than on a
 * user's phone.
 */
class UrlVerdictAccuracyTest {

    // ── Host-name brand matching ────────────────────────────────────────────────────────────

    @Test
    fun `a brand's own domain is never an impersonation of itself`() {
        assertNull(BrandRegistry.impersonationIn("google.com"))
        assertNull(BrandRegistry.impersonationIn("www.google.com"))
        assertNull(BrandRegistry.impersonationIn("accounts.google.com"))
        assertNull(BrandRegistry.impersonationIn("paypal.com"))
        assertNull(BrandRegistry.impersonationIn("amazonaws.com"))
        assertNull(BrandRegistry.impersonationIn("microsoftonline.com"))
    }

    /** The exact substring collisions that made ordinary domains look like impersonation. */
    @Test
    fun `ordinary words containing a short brand token are not impersonation`() {
        assertNull("purchase.com contains 'chase'", BrandRegistry.impersonationIn("purchase.com"))
        assertNull("backups.io contains 'ups'", BrandRegistry.impersonationIn("backups.io"))
        assertNull("pineapple.com contains 'apple'", BrandRegistry.impersonationIn("pineapple.com"))
        assertNull("first-national.com contains 'irs'", BrandRegistry.impersonationIn("first-national.com"))
        assertNull("groups.io contains 'ups'", BrandRegistry.impersonationIn("groups.io"))
    }

    @Test
    fun `a brand name on someone else's domain is impersonation`() {
        val paypal = BrandRegistry.impersonationIn("paypal-secure-login.tk")
        assertNotNull(paypal)
        assertEquals("PayPal", paypal!!.brand.display)
        assertEquals(BrandRegistry.MatchKind.LABEL, paypal.kind)

        assertNotNull("whole-label match for a short token", BrandRegistry.impersonationIn("ups-tracking-update.tk"))
        assertNotNull("substring match for a distinctive token", BrandRegistry.impersonationIn("paypalsecure.tk"))
        assertNotNull(BrandRegistry.impersonationIn("login.microsoft.verify-account.xyz"))
    }

    @Test
    fun `leetspeak look-alike domains are caught`() {
        val payPa1 = BrandRegistry.impersonationIn("paypa1.com")
        assertNotNull(payPa1)
        assertEquals(BrandRegistry.MatchKind.LOOKALIKE, payPa1!!.kind)
        assertNotNull(BrandRegistry.impersonationIn("g00gle.com"))
        assertNotNull(BrandRegistry.impersonationIn("micros0ft.net"))
    }

    @Test
    fun `registrable domain handles multi-label public suffixes`() {
        assertEquals("google.com", BrandRegistry.registrableDomain("www.google.com"))
        assertEquals("bbc.co.uk", BrandRegistry.registrableDomain("www.bbc.co.uk"))
        assertEquals("example.com", BrandRegistry.registrableDomain("a.b.c.example.com"))
    }

    // ── Page-content matching ───────────────────────────────────────────────────────────────

    /** Verbatim shapes from google.com's own homepage, which used to trip two findings. */
    @Test
    fun `markup and scripts are never searched for brand names`() {
        val html = """
            <html><head><title>Google</title>
            <style>.gb_ed:first-child{padding-left:0}</style>
            <script>var isEdge = ua.indexOf("microsoft edge") >= 0;</script>
            </head><body><p>Search the web.</p></body></html>
        """.trimIndent()
        val text = ContentInspector.visibleText(html)
        assertFalse("CSS must not leak into visible text", text.contains("first-child"))
        assertFalse("scripts must not leak into visible text", text.lowercase().contains("microsoft"))

        val report = ContentInspector.analyze(html, "www.google.com")
        assertTrue("no impersonation finding for a site's own homepage", report.findings.none { it.severity >= 3 })
    }

    @Test
    fun `merely naming a brand is not phishing without credential collection`() {
        val html = "<html><head><title>News</title></head><body>" +
            "<p>PayPal announced results today, and Microsoft responded.</p></body></html>"
        val report = ContentInspector.analyze(html, "example-news.com")
        assertTrue("an article about a company is not an impersonation", report.findings.none { it.severity >= 3 })
    }

    @Test
    fun `a brand plus a password box on the wrong domain is phishing`() {
        val html = "<html><head><title>Sign in</title></head><body>" +
            "<h1>Sign in to your PayPal account</h1>" +
            "<input type=\"password\" name=\"pw\"></body></html>"
        val report = ContentInspector.analyze(html, "paypal-secure.tk")
        assertTrue(report.hasPasswordField)
        assertTrue("impersonation + credentials must be flagged", report.findings.any { it.severity >= 3 })
    }

    @Test
    fun `a brand's own sign-in page is not flagged as impersonating itself`() {
        val html = "<html><head><title>Sign in - Google Accounts</title></head><body>" +
            "<h1>Sign in to your Google Account</h1><input type=\"password\"></body></html>"
        val report = ContentInspector.analyze(html, "accounts.google.com")
        assertTrue(report.findings.none { it.severity >= 3 })
    }

    @Test
    fun `whole-word matching does not fire on substrings`() {
        assertFalse(BrandRegistry.containsWord("the first child element", "irs"))
        assertFalse(BrandRegistry.containsWord("we make backups nightly", "ups"))
        assertFalse(BrandRegistry.containsWord("complete your purchase", "chase"))
        assertTrue(BrandRegistry.containsWord("sign in to chase online", "chase"))
        assertTrue(BrandRegistry.containsWord("your ups delivery", "ups"))
    }

    // ── Verdict scoring ─────────────────────────────────────────────────────────────────────

    private fun safe(source: String, weight: SignalWeight) = UrlSignal(source, Verdict.SAFE, "", weight)

    /** The exact shape of a `google.com` check for a user with no API keys configured. */
    @Test
    fun `an established site with clean checks and no flags is safe`() {
        val outcome = UrlVerdictScoring.evaluate(
            UrlVerdictScoring.Input(
                signals = listOf(
                    safe("Cloudflare security DNS", SignalWeight.DEFINITIVE),
                    safe("Domain age (RDAP)", SignalWeight.STRONG),
                    safe("TLS certificate", SignalWeight.STRONG),
                    safe("Page content", SignalWeight.SUPPORTING),
                ),
                flags = emptyList(),
                hasAnyEvidence = true,
            ),
        )
        assertEquals(Verdict.SAFE, outcome.verdict)
    }

    /**
     * The regression that mattered most: a legitimate small site on a cheap TLD used to be
     * condemned by that one low-severity note alone, with no way for years of clean history to
     * outweigh it.
     */
    @Test
    fun `one low-severity note cannot outweigh strong positive evidence`() {
        val outcome = UrlVerdictScoring.evaluate(
            UrlVerdictScoring.Input(
                signals = listOf(
                    safe("Cloudflare security DNS", SignalWeight.DEFINITIVE),
                    safe("Domain age (RDAP)", SignalWeight.STRONG),
                    safe("TLS certificate", SignalWeight.STRONG),
                ),
                flags = listOf(UrlHeuristics.Flag(1, "Uses a \".xyz\" domain ending frequently abused for scam sites")),
                hasAnyEvidence = true,
            ),
        )
        assertEquals(Verdict.SAFE, outcome.verdict)
    }

    @Test
    fun `a blocklist hit is decisive no matter what else looks fine`() {
        val outcome = UrlVerdictScoring.evaluate(
            UrlVerdictScoring.Input(
                signals = listOf(
                    UrlSignal("Cloudflare security DNS", Verdict.MALICIOUS, "blocked", SignalWeight.DEFINITIVE),
                    safe("Domain age (RDAP)", SignalWeight.STRONG),
                    safe("TLS certificate", SignalWeight.STRONG),
                ),
                flags = emptyList(),
                hasAnyEvidence = true,
            ),
        )
        assertEquals(Verdict.MALICIOUS, outcome.verdict)
    }

    /** A free certificate must not buy a look-alike domain its way back to safe. */
    @Test
    fun `brand impersonation on a new domain is malicious despite a valid certificate`() {
        val outcome = UrlVerdictScoring.evaluate(
            UrlVerdictScoring.Input(
                signals = listOf(
                    safe("Cloudflare security DNS", SignalWeight.DEFINITIVE),
                    UrlSignal("Domain age (RDAP)", Verdict.SUSPICIOUS, "registered 2 days ago", SignalWeight.STRONG),
                    safe("TLS certificate", SignalWeight.STRONG),
                    UrlSignal("Page content", Verdict.SUSPICIOUS, "asks for a password", SignalWeight.STRONG),
                ),
                flags = listOf(
                    UrlHeuristics.Flag(3, "Domain uses PayPal's name but isn't one of PayPal's real domains"),
                    UrlHeuristics.Flag(1, "Uses a \".tk\" domain ending frequently abused for scam sites"),
                ),
                hasAnyEvidence = true,
            ),
        )
        assertEquals(Verdict.MALICIOUS, outcome.verdict)
    }

    @Test
    fun `a domain nothing could be learned about is unknown, not safe`() {
        val outcome = UrlVerdictScoring.evaluate(
            UrlVerdictScoring.Input(signals = emptyList(), flags = emptyList(), hasAnyEvidence = false),
        )
        assertEquals(Verdict.UNKNOWN, outcome.verdict)
    }

    // ── End-to-end on-device analysis ───────────────────────────────────────────────────────

    @Test
    fun `plain well-known domains raise no on-device flags at all`() {
        listOf("google.com", "https://www.wikipedia.org", "https://github.com", "https://www.bbc.co.uk").forEach { url ->
            assertTrue("$url should raise no flags, got ${UrlHeuristics.analyze(url)}", UrlHeuristics.analyze(url).isEmpty())
        }
    }

    @Test
    fun `genuinely deceptive links still raise severity-3 flags`() {
        assertTrue(UrlHeuristics.analyze("http://192.168.1.50/login").any { it.severity >= 3 })
        assertTrue(UrlHeuristics.analyze("https://paypal-secure.tk/verify").any { it.severity >= 3 })
        assertTrue(UrlHeuristics.analyze("https://user@evil.example.com/").any { it.severity >= 3 })
    }

    // ── Cloudflare threat-DNS reading ───────────────────────────────────────────────────────

    /** Response shapes captured live from Cloudflare's own documented test endpoints. */
    @Test
    fun `cloudflare sinkhole answers are read as a block`() {
        val blockedA = DohResponse(Status = 0, Answer = listOf(DohAnswer(data = "0.0.0.0")), Comment = listOf("EDE(16): Censored"))
        val blockedAaaa = DohResponse(Status = 0, Answer = listOf(DohAnswer(data = "::")))
        val clean = DohResponse(Status = 0, Answer = listOf(DohAnswer(data = "142.250.72.14")))

        assertTrue(ThreatDnsVerdictReader.isBlocked(blockedA))
        assertTrue(ThreatDnsVerdictReader.isBlocked(blockedAaaa))
        assertFalse(ThreatDnsVerdictReader.isBlocked(clean))
        assertTrue(ThreatDnsVerdictReader.isClean(clean))
        assertFalse("a blocked answer is never also 'clean'", ThreatDnsVerdictReader.isClean(blockedA))
    }

    @Test
    fun `a failed lookup is neither blocked nor clean`() {
        val nxdomain = DohResponse(Status = 3)
        assertFalse(ThreatDnsVerdictReader.isBlocked(nxdomain))
        assertFalse(ThreatDnsVerdictReader.isClean(nxdomain))
    }

    // ── RDAP date parsing ───────────────────────────────────────────────────────────────────

    /**
     * Registries disagree on date format. `Instant.parse` handles the `Z` form only, so the offset
     * form silently cost the domain age — the strongest positive signal there is — at every
     * registry that uses it.
     */
    @Test
    fun `rdap dates parse in both the Z and numeric-offset forms`() {
        assertNotNull(TechnicalInspector.parseRdapInstant("1997-09-15T04:00:00Z"))
        assertNotNull(TechnicalInspector.parseRdapInstant("1997-09-15T04:00:00-04:00"))
        assertNull(TechnicalInspector.parseRdapInstant("not a date"))
    }

    @Test
    fun `domain age reads back in years and months, not raw days`() {
        val domain = DomainRegistration(registrar = null, registeredOn = null, expiresOn = null, ageDays = 10_400)
        assertEquals("28 years, 6 months", domain.ageHuman)
        assertEquals("1 year", DomainRegistration(null, null, null, ageDays = 365).ageHuman)
        assertEquals("6 days", DomainRegistration(null, null, null, ageDays = 6).ageHuman)
    }
}
