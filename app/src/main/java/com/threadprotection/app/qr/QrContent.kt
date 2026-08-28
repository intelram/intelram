package com.threadprotection.app.qr

/**
 * What a decoded QR payload actually is.
 *
 * Root-cause fix this exists for: every decoded payload used to be handed straight to
 * `ThreatIntelRepository.checkUrl()`, whatever it was. A Wi-Fi join string, a contact card or a
 * plain shopping list was normalised as a URL, sent to reputation services, and reported back with
 * a verdict about a "site" that was never a site. That is both wrong and a privacy leak: the user's
 * home Wi-Fi password went out to a third-party API as though it were a web address.
 */
enum class QrContentType {
    URL,
    PLAIN_TEXT,
    CONTACT,
    WIFI,
    EMAIL,
    PHONE,
    SMS,
    CALENDAR_EVENT,
    GEO,
    PAYMENT,
    APP_DEEP_LINK,
    OTP_AUTH,
    /** Structured but not a format we parse — still shown honestly rather than mislabelled. */
    STRUCTURED_DATA,
}

/** How much of a concern the payload is *before* any network lookup. */
enum class QrRiskLevel { SAFE, INFO, CAUTION, DANGER }

/**
 * One parsed field from the payload, for the "Parsed fields" section of the result.
 * [sensitive] fields are masked in the UI by default and never leave the device.
 */
data class QrField(val label: String, val value: String, val sensitive: Boolean = false)

/**
 * The structured result of classifying one decoded payload — the shape the scan result screen
 * renders and the security pipeline consumes.
 */
data class QrAnalysis(
    /** Exactly what the decoder returned, unmodified. */
    val rawPayload: String,
    val type: QrContentType,
    val typeLabel: String,
    val parsedFields: List<QrField>,
    /** Plain-language description for a non-technical user. */
    val explanation: String,
    /** Technical metadata — encoding notes, scheme, byte length, decoder used. */
    val metadata: List<QrField>,
    val recommendedAction: String,
    val risk: QrRiskLevel,
    /** Why this risk level, in the user's words. Empty when nothing is wrong. */
    val riskNotes: List<String>,
    /**
     * The URL to submit for reputation analysis, or null when there is nothing web-facing to check.
     *
     * This is the gate that stops non-URL payloads being sent to third-party services. Null means
     * the analysis is complete on-device and nothing is transmitted.
     */
    val urlToCheck: String? = null,
)
