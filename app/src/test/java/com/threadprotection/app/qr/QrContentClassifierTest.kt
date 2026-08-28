package com.threadprotection.app.qr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Test cases for QR payload classification.
 *
 * The privacy assertions matter as much as the parsing ones: [QrAnalysis.urlToCheck] is the single
 * gate that decides whether a payload is sent to a third-party reputation service, and a regression
 * there would leak Wi-Fi passwords and 2FA secrets off the device.
 */
class QrContentClassifierTest {

    private fun field(a: QrAnalysis, label: String) = a.parsedFields.firstOrNull { it.label == label }?.value

    // ── URLs ────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `an https link is classified as a URL and submitted for checking`() {
        val a = QrContentClassifier.classify("https://example.com/page?a=1")
        assertEquals(QrContentType.URL, a.type)
        assertEquals("https://example.com/page?a=1", a.urlToCheck)
        assertEquals("example.com", field(a, "Domain"))
    }

    /** A great many printed codes carry a bare domain with no scheme at all. */
    @Test
    fun `a bare domain is treated as a link and normalised to https`() {
        val a = QrContentClassifier.classify("example.com/offer")
        assertEquals(QrContentType.URL, a.type)
        assertEquals("https://example.com/offer", a.urlToCheck)
        assertTrue(a.riskNotes.any { it.contains("bare address") })
    }

    @Test
    fun `an unencrypted link is flagged`() {
        val a = QrContentClassifier.classify("http://example.com")
        assertEquals(QrRiskLevel.CAUTION, a.risk)
        assertTrue(a.riskNotes.any { it.contains("not encrypted") })
    }

    @Test
    fun `an at-sign address obfuscation is flagged`() {
        val a = QrContentClassifier.classify("https://www.paypal.com@evil.example/login")
        assertTrue(a.riskNotes.any { it.contains("@ sign") })
    }

    @Test
    fun `a punycode lookalike domain is flagged`() {
        val a = QrContentClassifier.classify("https://xn--pypal-4ve.com/login")
        assertTrue(a.riskNotes.any { it.contains("punycode") })
    }

    @Test
    fun `a raw IP destination is flagged`() {
        val a = QrContentClassifier.classify("http://192.168.1.50/setup")
        assertTrue(a.riskNotes.any { it.contains("raw IP") })
    }

    @Test
    fun `tracking parameters are surfaced`() {
        val a = QrContentClassifier.classify("https://shop.example/x?utm_source=poster&fbclid=abc")
        val trackers = field(a, "Tracking parameters").orEmpty()
        assertTrue(trackers.contains("utm_source"))
        assertTrue(trackers.contains("fbclid"))
    }

    // ── Wi-Fi: parsed on-device, never transmitted ──────────────────────────────────────────

    @Test
    fun `a wifi payload is parsed and never sent anywhere`() {
        val a = QrContentClassifier.classify("WIFI:S:HomeNet;T:WPA;P:s3cr3t;;")
        assertEquals(QrContentType.WIFI, a.type)
        assertEquals("HomeNet", field(a, "Network name"))
        assertEquals("s3cr3t", field(a, "Password"))
        assertNull("a Wi-Fi password must never be sent to a reputation service", a.urlToCheck)
    }

    @Test
    fun `a wifi password is marked sensitive so the UI masks it`() {
        val a = QrContentClassifier.classify("WIFI:S:HomeNet;T:WPA;P:s3cr3t;;")
        assertTrue(a.parsedFields.first { it.label == "Password" }.sensitive)
    }

    /** A naive split on ';' corrupts an escaped password, producing one that silently won't connect. */
    @Test
    fun `an escaped wifi password keeps its special characters`() {
        val a = QrContentClassifier.classify("""WIFI:S:Cafe;T:WPA;P:pa\;ss\:word;;""")
        assertEquals("pa;ss:word", field(a, "Password"))
    }

    @Test
    fun `an open network is flagged as a caution`() {
        val a = QrContentClassifier.classify("WIFI:S:FreeWiFi;T:nopass;;")
        assertEquals(QrRiskLevel.CAUTION, a.risk)
        assertTrue(a.riskNotes.any { it.contains("no password") })
    }

    @Test
    fun `a WEP network is flagged`() {
        val a = QrContentClassifier.classify("WIFI:S:Old;T:WEP;P:1234;;")
        assertTrue(a.riskNotes.any { it.contains("WEP") })
    }

    // ── two-factor secrets: the most sensitive payload ──────────────────────────────────────

    @Test
    fun `an otpauth secret is never sent for a reputation check despite being a URL`() {
        val a = QrContentClassifier.classify("otpauth://totp/ACME:alice@example.com?secret=JBSWY3DPEHPK3PXP&issuer=ACME")
        assertEquals(QrContentType.OTP_AUTH, a.type)
        assertNull("a 2FA seed must never leave the device", a.urlToCheck)
        assertEquals("ACME", field(a, "Service"))
        assertTrue(a.parsedFields.first { it.label == "Secret key" }.sensitive)
    }

    // ── other formats ───────────────────────────────────────────────────────────────────────

    @Test
    fun `a vcard is parsed as a contact`() {
        val a = QrContentClassifier.classify(
            "BEGIN:VCARD\nVERSION:3.0\nFN:Jane Roe\nORG:Acme\nTEL:+441234567890\nEMAIL:jane@acme.test\nEND:VCARD",
        )
        assertEquals(QrContentType.CONTACT, a.type)
        assertEquals("Jane Roe", field(a, "Name"))
        assertEquals("jane@acme.test", field(a, "Email"))
        assertNull(a.urlToCheck)
    }

    @Test
    fun `a mecard is parsed as a contact`() {
        val a = QrContentClassifier.classify("MECARD:N:Roe,Jane;TEL:+441234567890;EMAIL:jane@acme.test;;")
        assertEquals(QrContentType.CONTACT, a.type)
        assertEquals("+441234567890", field(a, "Phone"))
    }

    @Test
    fun `a mailto with a prefilled body is flagged`() {
        val a = QrContentClassifier.classify("mailto:x@y.test?subject=Hi&body=Please%20send%20money")
        assertEquals(QrContentType.EMAIL, a.type)
        assertEquals("Please send money", field(a, "Message"))
        assertEquals(QrRiskLevel.CAUTION, a.risk)
        assertNull(a.urlToCheck)
    }

    @Test
    fun `a premium rate phone number is treated as dangerous`() {
        val a = QrContentClassifier.classify("tel:+449001234567")
        assertEquals(QrContentType.PHONE, a.type)
        assertEquals(QrRiskLevel.DANGER, a.risk)
    }

    @Test
    fun `an ordinary phone number is not alarming`() {
        val a = QrContentClassifier.classify("tel:+441234567890")
        assertEquals(QrRiskLevel.INFO, a.risk)
    }

    @Test
    fun `an sms with a prefilled body is flagged`() {
        val a = QrContentClassifier.classify("SMSTO:+441234567890:JOIN")
        assertEquals(QrContentType.SMS, a.type)
        assertEquals("JOIN", field(a, "Message"))
        assertEquals(QrRiskLevel.CAUTION, a.risk)
    }

    @Test
    fun `a geo location is parsed and safe`() {
        val a = QrContentClassifier.classify("geo:51.5074,-0.1278")
        assertEquals(QrContentType.GEO, a.type)
        assertEquals("51.5074", field(a, "Latitude"))
        assertEquals(QrRiskLevel.SAFE, a.risk)
    }

    @Test
    fun `a calendar event is parsed`() {
        val a = QrContentClassifier.classify("BEGIN:VEVENT\nSUMMARY:Standup\nDTSTART:20260901T090000Z\nEND:VEVENT")
        assertEquals(QrContentType.CALENDAR_EVENT, a.type)
        assertEquals("Standup", field(a, "Event"))
    }

    @Test
    fun `a UPI payment is parsed and always cautioned`() {
        val a = QrContentClassifier.classify("upi://pay?pa=merchant@bank&pn=Shop&am=250&cu=INR")
        assertEquals(QrContentType.PAYMENT, a.type)
        assertEquals("merchant@bank", field(a, "Pay to"))
        assertEquals("250", field(a, "Amount"))
        assertEquals(QrRiskLevel.CAUTION, a.risk)
        assertNull(a.urlToCheck)
    }

    @Test
    fun `an EMVCo merchant code is recognised`() {
        val a = QrContentClassifier.classify("000201010211...")
        assertEquals(QrContentType.PAYMENT, a.type)
    }

    @Test
    fun `a crypto address is parsed as a payment`() {
        val a = QrContentClassifier.classify("bitcoin:1A1zP1eP5QGefi2DMPTfTL5SLmv7DivfNa?amount=0.1")
        assertEquals(QrContentType.PAYMENT, a.type)
        assertEquals("1A1zP1eP5QGefi2DMPTfTL5SLmv7DivfNa", field(a, "Address"))
    }

    @Test
    fun `an app deep link is never presented as safe`() {
        val a = QrContentClassifier.classify("myapp://transfer?to=someone&amount=500")
        assertEquals(QrContentType.APP_DEEP_LINK, a.type)
        assertEquals(QrRiskLevel.CAUTION, a.risk)
        assertNull(a.urlToCheck)
    }

    @Test
    fun `plain text is plain text and goes nowhere`() {
        val a = QrContentClassifier.classify("Table 14 — ask for Sam")
        assertEquals(QrContentType.PLAIN_TEXT, a.type)
        assertEquals(QrRiskLevel.SAFE, a.risk)
        assertNull(a.urlToCheck)
    }

    @Test
    fun `an empty payload does not crash`() {
        val a = QrContentClassifier.classify("   ")
        assertEquals("Empty code", a.typeLabel)
        assertNull(a.urlToCheck)
    }

    // ── the privacy invariant, stated once over everything ──────────────────────────────────

    @Test
    fun `only web links are ever submitted for a reputation check`() {
        val payloads = listOf(
            "WIFI:S:Net;T:WPA;P:pw;;",
            "otpauth://totp/A:b?secret=X",
            "BEGIN:VCARD\nFN:A\nEND:VCARD",
            "MECARD:N:A;;",
            "mailto:a@b.test",
            "tel:+441234567890",
            "SMSTO:+441234567890:hi",
            "geo:1,2",
            "BEGIN:VEVENT\nSUMMARY:X\nEND:VEVENT",
            "upi://pay?pa=a@b",
            "bitcoin:abc",
            "myapp://do",
            "just some text",
        )
        payloads.forEach { p ->
            assertNull("$p must not be sent off-device", QrContentClassifier.classify(p).urlToCheck)
        }
        // ...and the one that should be.
        assertNotNull(QrContentClassifier.classify("https://example.com").urlToCheck)
    }

    @Test
    fun `every classification explains itself and recommends an action`() {
        val payloads = listOf(
            "https://example.com", "WIFI:S:N;T:WPA;P:p;;", "tel:+441234567890",
            "geo:1,2", "plain", "upi://pay?pa=a@b", "otpauth://totp/A:b?secret=X",
        )
        payloads.forEach { p ->
            val a = QrContentClassifier.classify(p)
            assertTrue("$p should explain itself", a.explanation.length > 15)
            assertTrue("$p should recommend an action", a.recommendedAction.isNotBlank())
            assertEquals("raw payload must be preserved verbatim", p, a.rawPayload)
        }
    }
}
