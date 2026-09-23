package com.threadprotection.app.network

/**
 * Pure text extraction for a pasted/shared email — no network, no I/O. Kept deliberately simple and
 * testable: pull the sender's domain out of a "From" field, and every link out of the body, so
 * [ThreatIntelRepository.checkEmail] can hand both to infrastructure this app already has (the same
 * domain/DNS/TLS checks as [TechnicalInspector], the same [BrandRegistry] impersonation rules, the
 * same [ThreatIntelRepository.checkUrl] pipeline the QR scanner uses for every link).
 *
 * This app has no Gmail/OAuth integration, and isn't getting one for this — reading someone's inbox
 * needs a consent flow this app has no business asking for. Instead the user pastes or Android-shares
 * the sender and body text they're already looking at, same as they would to a person they're asking
 * "does this look right to you?".
 */
object EmailInspector {

    private val EMAIL_ADDRESS = Regex("[A-Za-z0-9._%+-]+@([A-Za-z0-9.-]+\\.[A-Za-z]{2,})")
    private val URL = Regex("""https?://[^\s<>"'\)\]]+""")

    /** Pulls the domain out of a "From" field in any shape it's likely to be pasted in —
     *  `alerts@paypal.com`, `"PayPal" <alerts@paypal.com>`, or just the bare address. */
    fun senderDomain(senderField: String): String? =
        EMAIL_ADDRESS.find(senderField)?.groupValues?.get(1)?.lowercase()?.trimEnd('.')

    /** Every distinct link in the body, in the order they appear, capped so a pasted wall of text
     *  (or an attacker padding a message with junk links) can't turn one email check into dozens of
     *  live network lookups. */
    fun extractUrls(body: String, limit: Int = 5): List<String> =
        URL.findAll(body)
            .map { it.value.trimEnd('.', ',', ';', ')', ']', '!', '?') }
            .distinct()
            .take(limit)
            .toList()
}
