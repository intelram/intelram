package com.threadprotection.app.network

/**
 * The brands phishing kits impersonate most, and — critically — the domains that legitimately
 * belong to them.
 *
 * **Root cause this exists to fix.** Both the URL check and the page-content check used to ask
 * `text.contains(brand)` against a raw brand list. That is catastrophically wrong at both ends:
 *
 * - In page HTML, `"irs"` matches the CSS pseudo-class `:first-child`, `"ups"` matches `groups` /
 *   `backups` / `popups`, `"chase"` matches `purchase`, and `"microsoft"` matches the browser
 *   user-agent sniffing (`"microsoft edge"`) that ships in half the JavaScript on the web. Google's
 *   own homepage tripped two "brand impersonation" findings; bbc.com tripped six. Every real site
 *   came back SUSPICIOUS, which is exactly what was reported.
 * - In host names, `purchase.com` matched `chase`, `backups.io` matched `ups`, and `pineapple.com`
 *   matched `apple`.
 *
 * The rules below replace substring matching with something defensible:
 *
 * 1. **An official domain is never an impersonation.** [Brand.officialDomains] is checked first, so
 *    `google.com`, `googleapis.com` and `amazonaws.com` can't be flagged for containing their own
 *    brand name.
 * 2. **Short tokens must match a whole host label.** A token under [SUBSTRING_SAFE_LENGTH]
 *    characters (`ups`, `irs`, `dhl`, `apple`, `chase`, `fedex`, `usps`) collides with ordinary
 *    English too often to be matched as a substring, so `ups-tracking.tk` is flagged while
 *    `backups.io` is not. Longer, distinctive tokens (`paypal`, `microsoft`, `instagram`) are
 *    matched inside a label too, catching `paypalsecure.tk`.
 * 3. **Leetspeak lookalikes are caught exactly, not fuzzily.** `paypa1`, `g00gle` and `micros0ft`
 *    normalise onto a real brand token, which is unambiguous phishing. This is deliberately used
 *    instead of an edit-distance check, which would flag innocent names that merely rhyme.
 * 4. **Page content only counts as impersonation alongside credential collection.** A news article
 *    or a blog post naming a bank is not phishing; a page naming that bank *and* asking for a
 *    password on someone else's domain is. See [ContentInspector].
 */
object BrandRegistry {

    /**
     * Tokens shorter than this are matched only as a complete host label, never as a substring —
     * see rule 2 in the class doc. Six is the shortest length at which the brand tokens here stop
     * colliding with common English words.
     */
    private const val SUBSTRING_SAFE_LENGTH = 6

    data class Brand(
        /** How the brand is named back to the user. */
        val display: String,
        /** Lowercase tokens that identify the brand in a host name or in page text. */
        val tokens: Set<String>,
        /** Registrable domains (eTLD+1) that genuinely belong to the brand. */
        val officialDomains: Set<String>,
    )

    val BRANDS: List<Brand> = listOf(
        Brand("PayPal", setOf("paypal"), setOf("paypal.com", "paypal.me", "paypalobjects.com")),
        Brand("Google", setOf("google", "gmail"), setOf("google.com", "google.co.uk", "googleapis.com", "gmail.com", "youtube.com", "googleusercontent.com", "withgoogle.com", "goo.gl")),
        Brand("Apple", setOf("apple", "icloud"), setOf("apple.com", "icloud.com", "apple.news", "me.com")),
        Brand("Amazon", setOf("amazon"), setOf("amazon.com", "amazon.co.uk", "amazon.in", "amazon.de", "amazonaws.com", "aws.amazon.com", "primevideo.com")),
        Brand("Microsoft", setOf("microsoft", "outlook", "onedrive"), setOf("microsoft.com", "microsoftonline.com", "live.com", "outlook.com", "office.com", "office365.com", "azure.com", "windows.com", "msn.com", "sharepoint.com", "skype.com")),
        Brand("Netflix", setOf("netflix"), setOf("netflix.com", "nflxvideo.net")),
        Brand("Facebook", setOf("facebook"), setOf("facebook.com", "fb.com", "fbcdn.net", "meta.com", "messenger.com")),
        Brand("Instagram", setOf("instagram"), setOf("instagram.com", "cdninstagram.com")),
        Brand("WhatsApp", setOf("whatsapp"), setOf("whatsapp.com", "whatsapp.net", "wa.me")),
        Brand("Bank of America", setOf("bankofamerica", "bofa"), setOf("bankofamerica.com", "bofa.com", "mbna.com")),
        Brand("Chase", setOf("chase"), setOf("chase.com", "jpmorganchase.com", "jpmorgan.com")),
        Brand("Wells Fargo", setOf("wellsfargo"), setOf("wellsfargo.com", "wf.com")),
        Brand("the IRS", setOf("irs"), setOf("irs.gov")),
        Brand("USPS", setOf("usps"), setOf("usps.com", "usps.gov", "uspis.gov")),
        Brand("FedEx", setOf("fedex"), setOf("fedex.com")),
        Brand("UPS", setOf("ups"), setOf("ups.com")),
        Brand("DHL", setOf("dhl"), setOf("dhl.com", "dhl.de")),
        Brand("Coinbase", setOf("coinbase"), setOf("coinbase.com")),
        Brand("Binance", setOf("binance"), setOf("binance.com", "binance.us")),
    )

    /**
     * Characters phishers swap in to build a lookalike domain, mapped back to what they imitate.
     * Applied only to decide whether a label *becomes* a brand token once un-substituted, so a
     * name that was already a normal word can never be dragged into a match.
     */
    private val LEET = mapOf('1' to 'l', '0' to 'o', '3' to 'e', '5' to 's', '4' to 'a', '7' to 't', '@' to 'a', '$' to 's')

    private fun deLeet(s: String): String = s.map { LEET[it] ?: it }.joinToString("")

    /** Naive eTLD+1: exact for the vast majority of domains — no bundled public-suffix list. */
    fun registrableDomain(host: String): String {
        val labels = host.lowercase().trimEnd('.').split('.').filter { it.isNotBlank() }
        if (labels.size < 2) return host.lowercase()
        val secondLevel = labels[labels.size - 2]
        return if (secondLevel in MULTI_LABEL_SUFFIX_SECOND_LEVEL && labels.size >= 3) {
            labels.takeLast(3).joinToString(".")
        } else {
            labels.takeLast(2).joinToString(".")
        }
    }

    private val MULTI_LABEL_SUFFIX_SECOND_LEVEL = setOf("co", "com", "org", "gov", "ac", "net", "edu")

    /** True when [host] is (or sits under) one of [brand]'s own domains. */
    fun isOfficialDomain(host: String, brand: Brand): Boolean =
        registrableDomain(host) in brand.officialDomains

    /** True when [host] belongs to any brand in the registry — used as positive corroboration. */
    fun officialBrandFor(host: String): Brand? = BRANDS.firstOrNull { isOfficialDomain(host, it) }

    /** How a host name matched a brand it doesn't belong to. */
    enum class MatchKind { LABEL, LOOKALIKE }

    data class HostMatch(val brand: Brand, val token: String, val kind: MatchKind)

    /**
     * Finds a brand this host name imitates, or null when it doesn't imitate one.
     *
     * Returns null for every domain that legitimately belongs to the brand, and — by design — for
     * ordinary words that merely contain a short brand token (`purchase.com`, `backups.io`,
     * `pineapple.com`). See the class doc for why that distinction is the whole point.
     */
    fun impersonationIn(host: String): HostMatch? {
        val clean = host.lowercase().trimEnd('.')
        if (clean.isBlank()) return null
        // An official domain is never an impersonation of itself, and never of anyone else either:
        // google.com is not "impersonating Microsoft" because its JavaScript mentions Edge.
        if (officialBrandFor(clean) != null) return null

        val labels = clean.split('.', '-', '_').filter { it.isNotBlank() }
        for (brand in BRANDS) {
            for (token in brand.tokens) {
                // Rule 2: whole-label match always counts; substring only for distinctive tokens.
                val labelHit = labels.any { it == token } ||
                    (token.length >= SUBSTRING_SAFE_LENGTH && labels.any { it.contains(token) })
                if (labelHit) return HostMatch(brand, token, MatchKind.LABEL)
                // Rule 3: a label that only becomes the brand once leetspeak is undone.
                val lookalike = labels.any { label -> label != token && deLeet(label) == token }
                if (lookalike) return HostMatch(brand, token, MatchKind.LOOKALIKE)
            }
        }
        return null
    }

    /**
     * Finds a brand named in a page's **visible text** (never its markup or scripts — see the class
     * doc) that the hosting domain doesn't belong to. Matching is whole-word, so `:first-child`
     * can't be read as "IRS" and `purchase` can't be read as "Chase".
     */
    fun brandNamedInText(visibleText: String, host: String): Brand? {
        val lower = visibleText.lowercase()
        if (lower.isBlank()) return null
        return BRANDS.firstOrNull { brand ->
            !isOfficialDomain(host, brand) && brand.tokens.any { token -> containsWord(lower, token) }
        }
    }

    /** Whole-word containment — the single check that makes content matching trustworthy. */
    fun containsWord(haystackLower: String, needleLower: String): Boolean {
        var from = 0
        while (true) {
            val i = haystackLower.indexOf(needleLower, from)
            if (i < 0) return false
            val before = if (i == 0) ' ' else haystackLower[i - 1]
            val afterIndex = i + needleLower.length
            val after = if (afterIndex >= haystackLower.length) ' ' else haystackLower[afterIndex]
            if (!before.isLetterOrDigit() && !after.isLetterOrDigit()) return true
            from = i + 1
        }
    }
}
