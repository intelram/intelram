package com.threadprotection.app.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [EmailInspector] is pure text extraction — no network, no Android framework — so every case here
 * runs offline. These pin the shapes a pasted or Android-shared "From" field and email body
 * actually take, so a regression in the extraction regexes fails here rather than silently
 * producing a wrong (or missing) sender domain / link list inside a real email check.
 */
class EmailInspectorTest {

    // ── senderDomain ────────────────────────────────────────────────────────────────────────

    @Test
    fun `bare address extracts its domain`() {
        assertEquals("paypal.com", EmailInspector.senderDomain("alerts@paypal.com"))
    }

    @Test
    fun `display-name plus angle-bracket address extracts the domain`() {
        assertEquals("paypal.com", EmailInspector.senderDomain("\"PayPal\" <alerts@paypal.com>"))
        assertEquals("amazon.com", EmailInspector.senderDomain("Amazon Support <support@amazon.com>"))
    }

    @Test
    fun `address is lowercased and a trailing dot is trimmed`() {
        assertEquals("paypal.com", EmailInspector.senderDomain("Alerts@PayPal.COM"))
        assertEquals("example.com", EmailInspector.senderDomain("user@example.com."))
    }

    @Test
    fun `subdomain sender keeps the full host, not the registrable domain`() {
        // Whether "mail.example.com" should be treated as "example.com" is BrandRegistry's
        // job (registrableDomain), not EmailInspector's — it must hand back exactly what was
        // in the field.
        assertEquals("mail.example.com", EmailInspector.senderDomain("noreply@mail.example.com"))
    }

    @Test
    fun `no email address in the field returns null`() {
        assertNull(EmailInspector.senderDomain(""))
        assertNull(EmailInspector.senderDomain("PayPal Support"))
        assertNull(EmailInspector.senderDomain("not an address at all"))
    }

    @Test
    fun `first address wins when the field somehow has more than one`() {
        assertEquals("paypal.com", EmailInspector.senderDomain("alerts@paypal.com, other@example.com"))
    }

    // ── extractUrls ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `extracts a single link from body text`() {
        val body = "Please verify your account at https://paypal-secure.tk/verify now."
        assertEquals(listOf("https://paypal-secure.tk/verify"), EmailInspector.extractUrls(body))
    }

    @Test
    fun `preserves order and extracts multiple links`() {
        val body = "First http://a.example/one then https://b.example/two and https://c.example/three"
        assertEquals(
            listOf("http://a.example/one", "https://b.example/two", "https://c.example/three"),
            EmailInspector.extractUrls(body),
        )
    }

    @Test
    fun `duplicate links are deduplicated`() {
        val body = "Click https://example.com/x or, if that fails, https://example.com/x again."
        assertEquals(listOf("https://example.com/x"), EmailInspector.extractUrls(body))
    }

    @Test
    fun `result is capped at the given limit`() {
        val body = (1..8).joinToString(" ") { "https://example.com/$it" }
        val urls = EmailInspector.extractUrls(body, limit = 5)
        assertEquals(5, urls.size)
        assertEquals("https://example.com/1", urls.first())
        assertEquals("https://example.com/5", urls.last())
    }

    @Test
    fun `default limit is five`() {
        val body = (1..10).joinToString(" ") { "https://example.com/$it" }
        assertEquals(5, EmailInspector.extractUrls(body).size)
    }

    @Test
    fun `trailing punctuation and closing brackets are stripped from a link`() {
        val body = "See (https://example.com/path), or https://example.com/other. Also [https://example.com/x]!"
        val urls = EmailInspector.extractUrls(body)
        assertTrue(urls.contains("https://example.com/path"))
        assertTrue(urls.contains("https://example.com/other"))
        assertTrue(urls.contains("https://example.com/x"))
        urls.forEach { url ->
            assertTrue("$url should have no trailing punctuation", url.last().isLetterOrDigit() || url.last() == '/')
        }
    }

    @Test
    fun `body with no links returns an empty list`() {
        assertEquals(emptyList<String>(), EmailInspector.extractUrls("Just a plain message, nothing to click."))
    }

    // ── dmarcSignal-relevant shape (DmarcStatus itself, covered where it's produced) ───────────

    @Test
    fun `dmarc status distinguishes enforced from monitor-only policies`() {
        val reject = DmarcStatus(present = true, policy = "reject")
        val none = DmarcStatus(present = true, policy = "none")
        val absent = DmarcStatus(present = false, policy = null)
        assertTrue(reject.present && reject.policy == "reject")
        assertTrue(none.present && none.policy == "none")
        assertTrue(!absent.present)
    }
}
