package com.threadprotection.app.qr

import java.net.URI
import java.util.Locale

/**
 * Classifies a decoded QR payload into a structured, explainable result — entirely on-device.
 *
 * Nothing here touches the network. That is deliberate and is the privacy boundary of the scanner:
 * classification decides *whether* anything should be sent out at all, via [QrAnalysis.urlToCheck].
 * A Wi-Fi password, a contact card or a one-time-password secret is fully understood here and never
 * transmitted.
 *
 * Parsing follows the formats real-world QR codes actually use — the ZXing/Barcode Contents
 * conventions that phone camera apps and the ML Kit barcode parser also implement (`WIFI:`,
 * `MECARD:`, `BEGIN:VCARD`, `BEGIN:VEVENT`, `mailto:`, `tel:`, `SMSTO:`, `geo:`, `otpauth://`,
 * `upi://`) plus a heuristic bare-domain case, since a great many printed codes carry
 * `example.com` with no scheme at all.
 */
object QrContentClassifier {

    fun classify(raw: String): QrAnalysis {
        val payload = raw.trim()
        val metadata = baseMetadata(payload)

        return when {
            payload.isEmpty() -> empty(raw, metadata)
            payload.startsWith("WIFI:", ignoreCase = true) -> wifi(payload, metadata)
            payload.startsWith("BEGIN:VCARD", ignoreCase = true) -> vCard(payload, metadata)
            payload.startsWith("MECARD:", ignoreCase = true) -> meCard(payload, metadata)
            payload.startsWith("BEGIN:VEVENT", ignoreCase = true) ||
                payload.startsWith("BEGIN:VCALENDAR", ignoreCase = true) -> calendar(payload, metadata)
            payload.startsWith("mailto:", ignoreCase = true) -> email(payload, metadata)
            payload.startsWith("MATMSG:", ignoreCase = true) -> matMsg(payload, metadata)
            payload.startsWith("tel:", ignoreCase = true) -> phone(payload, metadata)
            payload.startsWith("SMSTO:", ignoreCase = true) ||
                payload.startsWith("sms:", ignoreCase = true) ||
                payload.startsWith("smsto:", ignoreCase = true) -> sms(payload, metadata)
            payload.startsWith("geo:", ignoreCase = true) -> geo(payload, metadata)
            payload.startsWith("otpauth://", ignoreCase = true) -> otpAuth(payload, metadata)
            isPayment(payload) -> payment(payload, metadata)
            looksLikeWebUrl(payload) -> url(payload, metadata)
            hasCustomScheme(payload) -> deepLink(payload, metadata)
            else -> plainText(payload, metadata)
        }
    }

    // ── web URLs ────────────────────────────────────────────────────────────────────────────

    /** http/https, or a bare `host.tld/...` with no scheme — extremely common on printed codes. */
    private fun looksLikeWebUrl(payload: String): Boolean {
        val lower = payload.lowercase(Locale.ROOT)
        if (lower.startsWith("http://") || lower.startsWith("https://")) return true
        if (payload.contains(' ') || payload.contains('\n')) return false
        // A bare domain: at least one dot, a plausible TLD, no other scheme in front.
        if (payload.contains("://")) return false
        val host = payload.substringBefore('/').substringBefore('?')
        return BARE_DOMAIN.matches(host)
    }

    private fun url(payload: String, metadata: List<QrField>): QrAnalysis {
        val hadScheme = payload.lowercase(Locale.ROOT).let { it.startsWith("http://") || it.startsWith("https://") }
        val normalised = if (hadScheme) payload else "https://$payload"
        val uri = runCatching { URI(normalised) }.getOrNull()
        val host = uri?.host.orEmpty()
        val scheme = uri?.scheme?.lowercase(Locale.ROOT).orEmpty()
        val notes = mutableListOf<String>()

        // Signals visible without contacting anything. The full reputation pipeline runs later,
        // but these are the ones worth stating even if every network source is unavailable.
        if (scheme == "http") {
            notes += "This link is not encrypted (http, not https). Anything you type on it can be read in transit."
        }
        if (!hadScheme) {
            notes += "The code contained a bare address with no https:// — it is being treated as https."
        }
        if (host.matches(IPV4)) {
            notes += "The link points at a raw IP address instead of a domain name, which legitimate sites rarely do."
        }
        if (host.count { it == '.' } >= 4) {
            notes += "Unusually deep subdomain nesting — a common way to make a hostile address look familiar."
        }
        // Punycode: a lookalike domain built from non-Latin characters that render like Latin ones.
        if (host.contains("xn--")) {
            notes += "The domain uses punycode, which can make a fake address look identical to a real one."
        }
        val at = normalised.substringAfter("://").substringBefore('/')
        if (at.contains('@')) {
            notes += "The address hides its real destination behind an @ sign — what you see before it is ignored by the browser."
        }
        val userInfoCreds = uri?.userInfo?.contains(':') == true
        if (userInfoCreds) notes += "The link carries an embedded username and password."

        val trackers = TRACKING_PARAMS.filter { p -> normalised.contains("$p=", ignoreCase = true) }
        val fields = buildList {
            add(QrField("Address", normalised))
            if (host.isNotBlank()) add(QrField("Domain", host))
            uri?.path?.takeIf { it.isNotBlank() && it != "/" }?.let { add(QrField("Path", it)) }
            uri?.query?.takeIf { it.isNotBlank() }?.let { add(QrField("Parameters", it)) }
            if (trackers.isNotEmpty()) add(QrField("Tracking parameters", trackers.joinToString(", ")))
        }
        return QrAnalysis(
            rawPayload = payload,
            type = QrContentType.URL,
            typeLabel = "Website link",
            parsedFields = fields,
            explanation = if (host.isBlank()) {
                "This code opens a web link."
            } else {
                "This code opens $host in your browser."
            },
            metadata = metadata + listOfNotNull(
                QrField("Scheme", scheme.ifBlank { "none" }),
                uri?.port?.takeIf { it > 0 }?.let { QrField("Port", it.toString()) },
            ),
            recommendedAction = "Check the destination below before opening it.",
            risk = if (notes.isEmpty()) QrRiskLevel.INFO else QrRiskLevel.CAUTION,
            riskNotes = notes,
            // The only branch that hands anything to the network.
            urlToCheck = normalised,
        )
    }

    /** A non-web scheme that isn't one of the known data formats — usually opens another app. */
    private fun hasCustomScheme(payload: String): Boolean =
        CUSTOM_SCHEME.matches(payload.substringBefore(":").trim()) && payload.contains(":")

    private fun deepLink(payload: String, metadata: List<QrField>): QrAnalysis {
        val scheme = payload.substringBefore(":").lowercase(Locale.ROOT)
        return QrAnalysis(
            rawPayload = payload,
            type = QrContentType.APP_DEEP_LINK,
            typeLabel = "App link",
            parsedFields = listOf(QrField("Scheme", scheme), QrField("Target", payload)),
            explanation = "This code opens an app on your phone rather than a website. It uses the \"$scheme\" scheme.",
            metadata = metadata,
            recommendedAction = "Only open this if you recognise the app it belongs to.",
            // An app link can carry an action the app performs immediately, and this app cannot
            // see what the target app will do with it — so it is never presented as safe.
            risk = QrRiskLevel.CAUTION,
            riskNotes = listOf(
                "App links can trigger an action inside another app straight away. This scanner cannot see what \"$scheme\" will do with it.",
            ),
        )
    }

    // ── Wi-Fi ───────────────────────────────────────────────────────────────────────────────

    private fun wifi(payload: String, metadata: List<QrField>): QrAnalysis {
        val body = payload.removePrefix("WIFI:").removePrefix("wifi:")
        val parts = splitEscaped(body)
        val ssid = parts["S"].orEmpty()
        val security = parts["T"].orEmpty().uppercase(Locale.ROOT)
        val password = parts["P"].orEmpty()
        val hidden = parts["H"].equals("true", ignoreCase = true)

        val open = security.isBlank() || security == "NOPASS"
        val notes = mutableListOf<String>()
        if (open) {
            notes += "This network has no password. Anyone nearby can see traffic that isn't itself encrypted."
        }
        if (security == "WEP") {
            notes += "This network uses WEP, which has been broken for years and offers effectively no protection."
        }
        if (hidden) notes += "The network is hidden, so your phone will broadcast its name wherever you go."

        return QrAnalysis(
            rawPayload = payload,
            type = QrContentType.WIFI,
            typeLabel = "Wi-Fi network",
            parsedFields = buildList {
                add(QrField("Network name", ssid))
                add(QrField("Security", if (open) "Open — no password" else security))
                if (password.isNotBlank()) add(QrField("Password", password, sensitive = true))
                if (hidden) add(QrField("Hidden network", "Yes"))
            },
            explanation = if (open) {
                "This code joins the open Wi-Fi network \"$ssid\". Open networks are not private."
            } else {
                "This code joins the Wi-Fi network \"$ssid\" using the password stored in the code."
            },
            metadata = metadata,
            recommendedAction = "Only join if you recognise this network and trust whoever put up the code.",
            risk = if (open || security == "WEP") QrRiskLevel.CAUTION else QrRiskLevel.INFO,
            riskNotes = notes,
            // Nothing is sent anywhere: the password stays on this phone.
            urlToCheck = null,
        )
    }

    // ── contacts ────────────────────────────────────────────────────────────────────────────

    private fun vCard(payload: String, metadata: List<QrField>): QrAnalysis {
        val lines = payload.lines()
        fun value(prefix: String) = lines.firstOrNull { it.startsWith(prefix, ignoreCase = true) }
            ?.substringAfter(':')?.trim().orEmpty()
        val name = value("FN:").ifBlank { value("N:").replace(";", " ").trim() }
        return contact(payload, metadata, name, value("TEL"), value("EMAIL"), value("ORG"), value("URL"))
    }

    private fun meCard(payload: String, metadata: List<QrField>): QrAnalysis {
        val parts = splitEscaped(payload.removePrefix("MECARD:").removePrefix("mecard:"))
        return contact(
            payload, metadata,
            name = parts["N"].orEmpty().split(',').joinToString(" ").trim(),
            tel = parts["TEL"].orEmpty(),
            email = parts["EMAIL"].orEmpty(),
            org = parts["ORG"].orEmpty(),
            url = parts["URL"].orEmpty(),
        )
    }

    private fun contact(
        payload: String,
        metadata: List<QrField>,
        name: String,
        tel: String,
        email: String,
        org: String,
        url: String,
    ) = QrAnalysis(
        rawPayload = payload,
        type = QrContentType.CONTACT,
        typeLabel = "Contact card",
        parsedFields = buildList {
            if (name.isNotBlank()) add(QrField("Name", name))
            if (org.isNotBlank()) add(QrField("Organisation", org))
            if (tel.isNotBlank()) add(QrField("Phone", tel))
            if (email.isNotBlank()) add(QrField("Email", email))
            if (url.isNotBlank()) add(QrField("Website", url))
        },
        explanation = "This code adds ${name.ifBlank { "a contact" }} to your address book.",
        metadata = metadata,
        recommendedAction = "Check the details before saving. Saving a contact does not give anyone access to your phone.",
        risk = QrRiskLevel.INFO,
        riskNotes = if (url.isNotBlank()) {
            listOf("The card includes a website address. Treat it with the same care as any other link.")
        } else {
            emptyList()
        },
    )

    // ── messaging and telephony ─────────────────────────────────────────────────────────────

    private fun email(payload: String, metadata: List<QrField>): QrAnalysis {
        val withoutScheme = payload.removePrefix("mailto:").removePrefix("MAILTO:")
        val address = withoutScheme.substringBefore('?')
        val query = withoutScheme.substringAfter('?', "")
        val subject = paramOf(query, "subject")
        val body = paramOf(query, "body")
        return QrAnalysis(
            rawPayload = payload,
            type = QrContentType.EMAIL,
            typeLabel = "Email",
            parsedFields = buildList {
                add(QrField("To", address))
                if (subject.isNotBlank()) add(QrField("Subject", subject))
                if (body.isNotBlank()) add(QrField("Message", body))
            },
            explanation = "This code opens a new email to $address" +
                if (subject.isNotBlank() || body.isNotBlank()) ", with the subject and message already filled in." else ".",
            metadata = metadata,
            recommendedAction = "Read the pre-filled text before sending — you are the sender, not the code.",
            risk = if (body.isNotBlank()) QrRiskLevel.CAUTION else QrRiskLevel.INFO,
            riskNotes = if (body.isNotBlank()) {
                listOf("The message body is pre-written. Sending it would send that text from your address.")
            } else {
                emptyList()
            },
        )
    }

    private fun matMsg(payload: String, metadata: List<QrField>): QrAnalysis {
        val parts = splitEscaped(payload.removePrefix("MATMSG:").removePrefix("matmsg:"))
        val to = parts["TO"].orEmpty()
        val sub = parts["SUB"].orEmpty()
        val body = parts["BODY"].orEmpty()
        return QrAnalysis(
            rawPayload = payload,
            type = QrContentType.EMAIL,
            typeLabel = "Email",
            parsedFields = buildList {
                add(QrField("To", to))
                if (sub.isNotBlank()) add(QrField("Subject", sub))
                if (body.isNotBlank()) add(QrField("Message", body))
            },
            explanation = "This code opens a new email to $to.",
            metadata = metadata,
            recommendedAction = "Read the pre-filled text before sending.",
            risk = if (body.isNotBlank()) QrRiskLevel.CAUTION else QrRiskLevel.INFO,
            riskNotes = if (body.isNotBlank()) listOf("The message body is pre-written.") else emptyList(),
        )
    }

    private fun phone(payload: String, metadata: List<QrField>): QrAnalysis {
        val number = payload.removePrefix("tel:").removePrefix("TEL:").trim()
        val premium = PREMIUM_PREFIXES.any { number.replace(" ", "").startsWith(it) }
        return QrAnalysis(
            rawPayload = payload,
            type = QrContentType.PHONE,
            typeLabel = "Phone number",
            parsedFields = listOf(QrField("Number", number)),
            explanation = "This code dials $number.",
            metadata = metadata,
            recommendedAction = "Check the number before calling.",
            risk = if (premium) QrRiskLevel.DANGER else QrRiskLevel.INFO,
            riskNotes = if (premium) {
                listOf("This looks like a premium-rate number, which can charge a high amount per minute.")
            } else {
                emptyList()
            },
        )
    }

    private fun sms(payload: String, metadata: List<QrField>): QrAnalysis {
        val body = payload.substringAfter(':')
        val number = body.substringBefore(':').substringBefore('?')
        val message = if (body.contains(':')) body.substringAfter(':') else paramOf(body.substringAfter('?', ""), "body")
        return QrAnalysis(
            rawPayload = payload,
            type = QrContentType.SMS,
            typeLabel = "Text message",
            parsedFields = buildList {
                add(QrField("To", number))
                if (message.isNotBlank()) add(QrField("Message", message))
            },
            explanation = "This code opens a text message to $number" +
                if (message.isNotBlank()) " with the text already written." else ".",
            metadata = metadata,
            recommendedAction = "Read the message before sending it — premium shortcodes can charge you for a reply.",
            risk = if (message.isNotBlank()) QrRiskLevel.CAUTION else QrRiskLevel.INFO,
            riskNotes = if (message.isNotBlank()) {
                listOf("The text is pre-written. Some services charge you simply for sending a keyword to a shortcode.")
            } else {
                emptyList()
            },
        )
    }

    // ── other structured formats ────────────────────────────────────────────────────────────

    private fun calendar(payload: String, metadata: List<QrField>): QrAnalysis {
        val lines = payload.lines()
        fun value(prefix: String) = lines.firstOrNull { it.startsWith(prefix, ignoreCase = true) }
            ?.substringAfter(':')?.trim().orEmpty()
        return QrAnalysis(
            rawPayload = payload,
            type = QrContentType.CALENDAR_EVENT,
            typeLabel = "Calendar event",
            parsedFields = buildList {
                value("SUMMARY").takeIf { it.isNotBlank() }?.let { add(QrField("Event", it)) }
                value("DTSTART").takeIf { it.isNotBlank() }?.let { add(QrField("Starts", it)) }
                value("DTEND").takeIf { it.isNotBlank() }?.let { add(QrField("Ends", it)) }
                value("LOCATION").takeIf { it.isNotBlank() }?.let { add(QrField("Location", it)) }
            },
            explanation = "This code adds an event to your calendar.",
            metadata = metadata,
            recommendedAction = "Check the date and details before adding it.",
            risk = QrRiskLevel.INFO,
            riskNotes = emptyList(),
        )
    }

    private fun geo(payload: String, metadata: List<QrField>): QrAnalysis {
        val coords = payload.removePrefix("geo:").removePrefix("GEO:").substringBefore('?')
        val lat = coords.substringBefore(',')
        val lon = coords.substringAfter(',', "").substringBefore(',')
        return QrAnalysis(
            rawPayload = payload,
            type = QrContentType.GEO,
            typeLabel = "Map location",
            parsedFields = listOfNotNull(
                lat.takeIf { it.isNotBlank() }?.let { QrField("Latitude", it) },
                lon.takeIf { it.isNotBlank() }?.let { QrField("Longitude", it) },
            ),
            explanation = "This code opens a location in your maps app.",
            metadata = metadata,
            recommendedAction = "Opening a map location does not share your own position.",
            risk = QrRiskLevel.SAFE,
            riskNotes = emptyList(),
        )
    }

    /**
     * `otpauth://` — a two-factor authentication secret.
     *
     * Treated as the most sensitive payload the scanner can see. The secret is the second factor:
     * anyone who copies it can generate valid codes for that account indefinitely. It is masked in
     * the UI, and [QrAnalysis.urlToCheck] is deliberately null so it is never sent to any
     * reputation service, despite technically being a URL.
     */
    private fun otpAuth(payload: String, metadata: List<QrField>): QrAnalysis {
        val uri = runCatching { URI(payload) }.getOrNull()
        val label = uri?.path?.removePrefix("/").orEmpty()
        val query = uri?.query.orEmpty()
        val issuer = paramOf(query, "issuer")
        return QrAnalysis(
            rawPayload = payload,
            type = QrContentType.OTP_AUTH,
            typeLabel = "Two-factor authentication key",
            parsedFields = buildList {
                if (issuer.isNotBlank()) add(QrField("Service", issuer))
                if (label.isNotBlank()) add(QrField("Account", label))
                add(QrField("Secret key", paramOf(query, "secret"), sensitive = true))
            },
            explanation = "This code sets up two-factor authentication" +
                if (issuer.isNotBlank()) " for $issuer." else ".",
            metadata = metadata,
            recommendedAction = "Only scan this in your own authenticator app, and never share or photograph it.",
            risk = QrRiskLevel.CAUTION,
            riskNotes = listOf(
                "This code contains a secret key. Anyone who copies it can generate valid login codes for this account.",
                "It has not been sent anywhere — this scanner analysed it entirely on your phone.",
            ),
            urlToCheck = null,
        )
    }

    private fun isPayment(payload: String): Boolean {
        val lower = payload.lowercase(Locale.ROOT)
        // upi:// (India), bitcoin:/ethereum: (crypto), and EMVCo merchant codes, which are
        // TLV-encoded and start with payload-format-indicator "000201".
        return lower.startsWith("upi://") ||
            lower.startsWith("bitcoin:") ||
            lower.startsWith("ethereum:") ||
            lower.startsWith("bitcoincash:") ||
            payload.startsWith("000201")
    }

    private fun payment(payload: String, metadata: List<QrField>): QrAnalysis {
        val lower = payload.lowercase(Locale.ROOT)
        val scheme = payload.substringBefore(":").lowercase(Locale.ROOT)
        val fields = mutableListOf<QrField>()
        val kind: String
        when {
            lower.startsWith("upi://") -> {
                kind = "UPI payment"
                val query = payload.substringAfter('?', "")
                paramOf(query, "pa").takeIf { it.isNotBlank() }?.let { fields += QrField("Pay to", it) }
                paramOf(query, "pn").takeIf { it.isNotBlank() }?.let { fields += QrField("Payee name", it) }
                paramOf(query, "am").takeIf { it.isNotBlank() }?.let { fields += QrField("Amount", it) }
                paramOf(query, "cu").takeIf { it.isNotBlank() }?.let { fields += QrField("Currency", it) }
            }
            payload.startsWith("000201") -> {
                kind = "Merchant payment code"
                fields += QrField("Format", "EMVCo merchant QR")
            }
            else -> {
                kind = "Cryptocurrency payment"
                fields += QrField("Network", scheme)
                fields += QrField("Address", payload.substringAfter(':').substringBefore('?'))
            }
        }
        return QrAnalysis(
            rawPayload = payload,
            type = QrContentType.PAYMENT,
            typeLabel = kind,
            parsedFields = fields,
            explanation = "This code starts a payment. Money moves only if you confirm it in your payment app.",
            metadata = metadata,
            recommendedAction = "Check the payee and the amount in your payment app before confirming. Never pay a code you did not expect.",
            // Payment codes are the single most impersonated QR type — swapped stickers over real
            // merchant codes are a well-documented fraud — so this is never presented as routine.
            risk = QrRiskLevel.CAUTION,
            riskNotes = listOf(
                "Payment codes are commonly faked by sticking a new code over a real one. Confirm the payee name matches who you are actually paying.",
            ),
            urlToCheck = null,
        )
    }

    private fun plainText(payload: String, metadata: List<QrField>) = QrAnalysis(
        rawPayload = payload,
        type = QrContentType.PLAIN_TEXT,
        typeLabel = "Plain text",
        parsedFields = listOf(QrField("Text", payload)),
        explanation = "This code contains plain text. It does not open anything or change any setting.",
        metadata = metadata,
        recommendedAction = "Nothing happens unless you act on the text yourself.",
        risk = QrRiskLevel.SAFE,
        riskNotes = emptyList(),
        urlToCheck = null,
    )

    private fun empty(raw: String, metadata: List<QrField>) = QrAnalysis(
        rawPayload = raw,
        type = QrContentType.PLAIN_TEXT,
        typeLabel = "Empty code",
        parsedFields = emptyList(),
        explanation = "This code decoded successfully but contains nothing.",
        metadata = metadata,
        recommendedAction = "Nothing to act on.",
        risk = QrRiskLevel.INFO,
        riskNotes = emptyList(),
        urlToCheck = null,
    )

    // ── helpers ─────────────────────────────────────────────────────────────────────────────

    private fun baseMetadata(payload: String): List<QrField> = listOf(
        QrField("Payload length", "${payload.length} characters"),
        QrField("Encoded size", "${payload.toByteArray(Charsets.UTF_8).size} bytes"),
    )

    /**
     * Splits the `KEY:value;KEY:value;` form used by WIFI:, MECARD: and MATMSG:.
     *
     * Backslash escaping matters here and is easy to get wrong: a Wi-Fi password containing `;`
     * or `:` is escaped as `\;` / `\:`, and a naive `split(';')` corrupts it — producing a password
     * that silently fails to connect with no indication why.
     */
    private fun splitEscaped(body: String): Map<String, String> {
        val out = mutableMapOf<String, String>()
        val current = StringBuilder()
        var key: String? = null
        var i = 0
        while (i < body.length) {
            val c = body[i]
            when {
                c == '\\' && i + 1 < body.length -> { current.append(body[i + 1]); i++ }
                c == ':' && key == null -> { key = current.toString(); current.clear() }
                c == ';' -> {
                    if (key != null) out.putIfAbsent(key.uppercase(Locale.ROOT), current.toString())
                    key = null
                    current.clear()
                }
                else -> current.append(c)
            }
            i++
        }
        if (key != null && current.isNotEmpty()) out.putIfAbsent(key.uppercase(Locale.ROOT), current.toString())
        return out
    }

    private fun paramOf(query: String, name: String): String =
        query.split('&')
            .firstOrNull { it.startsWith("$name=", ignoreCase = true) }
            ?.substringAfter('=')
            ?.let { runCatching { java.net.URLDecoder.decode(it, "UTF-8") }.getOrDefault(it) }
            .orEmpty()

    private val BARE_DOMAIN = Regex("^[a-zA-Z0-9-]+(\\.[a-zA-Z0-9-]+)*\\.[a-zA-Z]{2,24}(:\\d+)?$")
    private val IPV4 = Regex("^\\d{1,3}(\\.\\d{1,3}){3}$")
    private val CUSTOM_SCHEME = Regex("^[a-zA-Z][a-zA-Z0-9+.-]{1,32}$")

    /** Common analytics parameters, flagged so the user can see they are being tracked. */
    private val TRACKING_PARAMS = listOf(
        "utm_source", "utm_medium", "utm_campaign", "utm_term", "utm_content",
        "fbclid", "gclid", "msclkid", "mc_eid", "igshid", "twclid",
    )

    /** Premium-rate ranges commonly abused in dial-a-charge QR fraud. */
    private val PREMIUM_PREFIXES = listOf("+449", "+4470", "1900", "+1900", "900")
}
