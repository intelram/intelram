package com.threadprotection.app.network

import com.threadprotection.app.data.StoredBreachMonitor
import com.threadprotection.app.data.StoredBreachRecord
import java.util.Locale

enum class BreachSeverity { CRITICAL, HIGH, MEDIUM, LOW }

/**
 * Not every "breach" is one site that got hacked. Stealer logs are credentials harvested by
 * malware on a victim's own device; combo lists are compilations of many older breaches. Telling
 * someone to "change your AlienStealerLogs password" would be meaningless — so these get their own
 * advice.
 */
enum class BreachKind { SITE, STEALER_LOGS, COMBO_LIST }

/** A general step for protecting an email address — see [BreachAdvisor.emailProtectionChecklist]. */
data class ProtectionTip(val title: String, val detail: String)

/** What a breach check means for alerting — see [BreachAdvisor.plan]. */
sealed interface BreachAlertPlan {
    data object None : BreachAlertPlan

    /** First time this address has been monitored: one summary, not one alert per old breach. */
    data class FirstLook(val breaches: List<BreachRecord>) : BreachAlertPlan

    /** Breaches that weren't there at the previous check. */
    data class NewBreaches(val breaches: List<BreachRecord>) : BreachAlertPlan
}

/**
 * Turns raw breach records into severity, specific next steps, and new-vs-already-known decisions.
 * Pure — no I/O, no Android — so the rules that decide what a user is told to do are unit-tested
 * (`BreachAdvisorTest`) rather than only read.
 *
 * Every recommendation is driven by what the breach actually exposed, using the exact data-class
 * labels XposedOrNot publishes ("Phone numbers", "Dates of birth", "Government issued IDs", …). A
 * breach that exposed only names and IP addresses doesn't get told to call its bank.
 */
object BreachAdvisor {

    private enum class Exposure { PASSWORD, RECOVERY_SECRET, SESSION_TOKEN, CARD, BANK_ACCOUNT, ACCOUNT_ACTIVITY, GOVERNMENT_ID, PHONE, BIRTH, ADDRESS, INTIMATE, USERNAME }

    private val RULES: List<Pair<Exposure, List<String>>> = listOf(
        Exposure.PASSWORD to listOf("password"),
        Exposure.RECOVERY_SECRET to listOf("security question", "maiden name"),
        Exposure.SESSION_TOKEN to listOf("auth token"),
        Exposure.CARD to listOf("credit card", "card data", "card details"),
        Exposure.BANK_ACCOUNT to listOf("bank account"),
        Exposure.ACCOUNT_ACTIVITY to listOf("account balance", "financial transaction", "income"),
        Exposure.GOVERNMENT_ID to listOf("government", "passport", "social security", "national id"),
        Exposure.PHONE to listOf("phone number"),
        Exposure.BIRTH to listOf("date of birth", "dates of birth", "year of birth", "years of birth", "place of birth", "places of birth"),
        Exposure.ADDRESS to listOf("physical address"),
        Exposure.INTIMATE to listOf("private message", "sexual", "religion", "ethnicit", "drug habit", "drink habit", "ai prompt"),
        Exposure.USERNAME to listOf("username"),
    )

    private fun exposuresOf(b: BreachRecord): Set<Exposure> {
        val lower = b.dataClasses.map { it.lowercase() }
        return RULES.filter { (_, needles) -> lower.any { dc -> needles.any { dc.contains(it) } } }
            .map { it.first }
            .toSet()
    }

    private fun passwordsAreEffectivelyPublic(b: BreachRecord) =
        b.passwordStorage == PasswordStorage.PLAINTEXT || b.passwordStorage == PasswordStorage.EASY_TO_CRACK

    private val STEALER_PHRASES = listOf("stealer log", "infostealer", "info-stealer")
    private val COMBO_PHRASES = listOf(
        "combo list", "combolist", "credential stuffing list", "aggregate collection",
        "collection of email addresses and passwords", "credential collection",
    )

    /** Read from the breach's own description — the per-address API doesn't say which kind it is. */
    fun kindOf(b: BreachRecord): BreachKind {
        val text = (b.name + " " + b.description.orEmpty()).lowercase()
        return when {
            STEALER_PHRASES.any { it in text } -> BreachKind.STEALER_LOGS
            COMBO_PHRASES.any { it in text } -> BreachKind.COMBO_LIST
            else -> BreachKind.SITE
        }
    }

    fun severityOf(b: BreachRecord): BreachSeverity {
        val e = exposuresOf(b)
        return when {
            // Malware captured these from a real browser session: the passwords are exact and current-ish.
            kindOf(b) == BreachKind.STEALER_LOGS -> BreachSeverity.CRITICAL
            Exposure.PASSWORD in e && passwordsAreEffectivelyPublic(b) -> BreachSeverity.CRITICAL
            Exposure.CARD in e || Exposure.BANK_ACCOUNT in e || Exposure.GOVERNMENT_ID in e -> BreachSeverity.CRITICAL
            Exposure.RECOVERY_SECRET in e || Exposure.SESSION_TOKEN in e -> BreachSeverity.CRITICAL
            Exposure.PASSWORD in e || Exposure.INTIMATE in e || Exposure.ACCOUNT_ACTIVITY in e -> BreachSeverity.HIGH
            Exposure.PHONE in e && Exposure.BIRTH in e -> BreachSeverity.HIGH
            Exposure.PHONE in e || Exposure.BIRTH in e || Exposure.ADDRESS in e || Exposure.USERNAME in e -> BreachSeverity.MEDIUM
            else -> BreachSeverity.LOW
        }
    }

    /** One plain-language line on how the site stored passwords, or null when none leaked. */
    fun passwordStorageNote(b: BreachRecord): String? {
        if (Exposure.PASSWORD !in exposuresOf(b)) return null
        return when (b.passwordStorage) {
            PasswordStorage.PLAINTEXT -> "Passwords were stored in plain text — anyone with the data can read them directly."
            PasswordStorage.EASY_TO_CRACK -> "Passwords were stored with weak hashing that can be cracked in minutes to hours."
            PasswordStorage.HARD_TO_CRACK -> "Passwords were stored with strong hashing. That slows attackers down but doesn't stop them for common or reused passwords."
            PasswordStorage.UNKNOWN -> "How passwords were stored isn't known, so assume they can be recovered."
        }
    }

    /** Specific next steps for this one breach, most urgent first. */
    fun actionsFor(b: BreachRecord): List<String> {
        return when (kindOf(b)) {
            BreachKind.STEALER_LOGS -> listOf(
                "This came from password-stealing malware on a device you used, not from one website. Run a malware scan on every computer and phone you sign in from.",
                "Then change the passwords saved in your browsers — email and banking first — and sign out of all sessions on those accounts so stolen logins stop working.",
                "Turn on two-step verification for your email and banking — an authenticator app or passkey is safer than SMS codes.",
                "Expect login attempts and phishing that use these exact details.",
            )
            BreachKind.COMBO_LIST -> listOf(
                "This is a list compiled from many earlier breaches, not one site. Change any password you've used on more than one account, and give each account its own.",
                "Turn on two-step verification for your email and banking — an authenticator app or passkey is safer than SMS codes.",
                "Expect automated login attempts using these details; a password manager and unique passwords make them useless.",
            )
            BreachKind.SITE -> siteActions(b)
        }
    }

    private fun siteActions(b: BreachRecord): List<String> {
        val e = exposuresOf(b)
        val site = b.name
        val actions = mutableListOf<String>()

        if (Exposure.PASSWORD in e) {
            actions += if (passwordsAreEffectivelyPublic(b)) {
                "Change your $site password now and treat the old one as public — then change it on every other account where you used the same or a similar password."
            } else {
                "Change your $site password, and on every other account where you reused it."
            }
            actions += "Turn on two-step verification for $site and for your email account — an authenticator app or passkey is safer than SMS codes."
        }
        if (Exposure.SESSION_TOKEN in e) {
            actions += "Sign out of $site on all devices so leaked login tokens stop working."
        }
        if (Exposure.RECOVERY_SECRET in e) {
            actions += "Change your security-question answers on important accounts (email, bank). Don't use real answers — treat them as extra passwords."
        }
        if (Exposure.CARD in e) {
            actions += "Contact your card issuer: ask for a replacement card and watch statements for payments you don't recognise."
        }
        if (Exposure.BANK_ACCOUNT in e) {
            actions += "Tell your bank your account number was exposed, and watch for direct debits or transfers you didn't set up."
        }
        if (Exposure.ACCOUNT_ACTIVITY in e) {
            actions += "Financial details were exposed, so scammers may quote real facts about your accounts. Your bank will never ask for your password or a one-time code by phone or message."
        }
        if (Exposure.GOVERNMENT_ID in e) {
            actions += "An ID number was exposed. Consider a credit freeze with your country's credit bureaus so no one can open credit in your name, and watch for loan or tax letters you didn't expect."
        }
        if (Exposure.PHONE in e) {
            actions += "Ask your mobile carrier for a SIM / port-out PIN to block SIM-swap attacks, and expect scam calls and texts that mention $site."
        }
        if (Exposure.BIRTH in e) {
            actions += "Your date of birth can pass identity checks — ask your bank and mobile carrier to require a PIN or password for account changes."
        }
        if (Exposure.INTIMATE in e) {
            actions += "Private details were exposed. If anyone threatens to publish them unless you pay, don't pay or reply — report it. These threats are usually bulk scams built from leaked data."
        }
        if (Exposure.ADDRESS in e) {
            actions += "Be wary of letters or parcels claiming to be from $site that ask you to pay or \"verify\" anything."
        }
        if (Exposure.USERNAME in e && Exposure.PASSWORD !in e) {
            actions += "If you use the same username elsewhere, attackers can link those accounts — check their passwords too."
        }
        actions += "Expect phishing emails that mention $site or quote details from this breach. Don't click links in them — go to the site yourself."
        return actions
    }

    /** General hardening for the email address itself, independent of any one breach. */
    fun emailProtectionChecklist(): List<ProtectionTip> = listOf(
        ProtectionTip(
            "Give your email its own password",
            "Your email account can reset every other password you have. Use a long, unique password for it that you use nowhere else — a password manager makes this practical.",
        ),
        ProtectionTip(
            "Turn on two-step verification",
            "Use an authenticator app, a passkey or a security key. SMS codes are better than nothing, but can be stolen with a SIM swap if your phone number has leaked.",
        ),
        ProtectionTip(
            "Check your recovery options",
            "In your email account's security settings, make sure the recovery phone number and backup email are yours. Attackers change these to lock you out.",
        ),
        ProtectionTip(
            "Look for hidden forwarding",
            "Check your mail settings for forwarding addresses and filters you didn't create. Attackers add silent forwarding so they keep reading your mail after you change your password.",
        ),
        ProtectionTip(
            "Review signed-in devices and connected apps",
            "Sign out any device you don't recognise, and remove third-party apps you no longer use that have access to your mail.",
        ),
        ProtectionTip(
            "Use aliases for sign-ups",
            "Services like Firefox Relay, SimpleLogin, DuckDuckGo Email Protection or Apple's Hide My Email give each site its own forwarding address. If one leaks, you know who leaked it and can shut just that one off. With Gmail, adding +shopname after your name works too, though it's easy to strip off.",
        ),
    )

    /** Most severe first, then most recently added to the breach database, then largest. */
    fun prioritized(breaches: List<BreachRecord>): List<BreachRecord> =
        breaches.sortedWith(
            compareBy<BreachRecord> { severityOf(it).ordinal }
                .thenByDescending { it.addedAt.orEmpty() }
                .thenByDescending { it.records ?: 0L },
        )

    /**
     * Decides what, if anything, this result should alert about. A failed check never alerts.
     * With no baseline for this address, it's a [BreachAlertPlan.FirstLook] (if anything was
     * found at all); otherwise only breaches absent from the baseline count as new.
     */
    fun plan(result: BreachCheckResult, baseline: StoredBreachMonitor?): BreachAlertPlan {
        if (!result.checked) return BreachAlertPlan.None
        val known = baseline?.takeIf { it.email.equals(result.email, ignoreCase = true) }?.knownBreachNames?.toSet()
        if (known == null) {
            return if (result.breaches.isEmpty()) BreachAlertPlan.None else BreachAlertPlan.FirstLook(prioritized(result.breaches))
        }
        val fresh = result.breaches.filter { it.name !in known }
        return if (fresh.isEmpty()) BreachAlertPlan.None else BreachAlertPlan.NewBreaches(prioritized(fresh))
    }

    /**
     * The baseline to store after a successful check. Names are unioned with the previous baseline
     * rather than replaced, so a breach that briefly drops out of the source's results (an outage,
     * a re-index) doesn't alert again as "new" when it comes back.
     */
    fun updatedBaseline(result: BreachCheckResult, previous: StoredBreachMonitor?, nowMs: Long): StoredBreachMonitor {
        val carried = previous?.takeIf { it.email.equals(result.email, ignoreCase = true) }?.knownBreachNames.orEmpty()
        return StoredBreachMonitor(
            email = result.email,
            knownBreachNames = (carried + result.breaches.map { it.name }).distinct(),
            lastCheckedAtMs = nowMs,
        )
    }

    fun BreachRecord.toStored() = StoredBreachRecord(
        name = name,
        date = date,
        dataClasses = dataClasses,
        records = records,
        domain = domain,
        description = description,
        industry = industry,
        passwordStorage = passwordStorage.name,
        verified = verified,
        addedAt = addedAt,
        referenceUrl = referenceUrl,
    )

    fun StoredBreachRecord.toRecord() = BreachRecord(
        name = name,
        date = date,
        dataClasses = dataClasses,
        records = records,
        domain = domain,
        description = description,
        industry = industry,
        passwordStorage = PasswordStorage.entries.firstOrNull { it.name == passwordStorage } ?: PasswordStorage.UNKNOWN,
        verified = verified,
        addedAt = addedAt,
        referenceUrl = referenceUrl,
    )

    /** "1.2M", "84K", "950" — record counts are shown in cards where space is tight. */
    fun compactCount(n: Long): String = when {
        n >= 1_000_000_000 -> String.format(Locale.US, "%.1fB", n / 1_000_000_000.0).replace(".0B", "B")
        n >= 1_000_000 -> String.format(Locale.US, "%.1fM", n / 1_000_000.0).replace(".0M", "M")
        n >= 1_000 -> "${n / 1_000}K"
        else -> n.toString()
    }
}
