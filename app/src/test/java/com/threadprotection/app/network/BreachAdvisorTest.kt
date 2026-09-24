package com.threadprotection.app.network

import com.threadprotection.app.data.StoredBreachMonitor
import com.threadprotection.app.network.BreachAdvisor.toRecord
import com.threadprotection.app.network.BreachAdvisor.toStored
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rules that decide what a breached user is told: how serious each breach is, which steps they
 * get, and whether a check counts as a new breach worth alerting about. Data-class labels below are
 * the exact strings XposedOrNot publishes.
 */
class BreachAdvisorTest {

    private fun breach(
        name: String = "ExampleSite",
        vararg dataClasses: String,
        storage: PasswordStorage = PasswordStorage.UNKNOWN,
        addedAt: String? = null,
        records: Long? = null,
    ) = BreachRecord(
        name = name,
        date = "2024",
        dataClasses = dataClasses.toList(),
        records = records,
        domain = null,
        passwordStorage = storage,
        addedAt = addedAt,
    )

    private fun result(email: String, vararg breaches: BreachRecord, checked: Boolean = true) =
        BreachCheckResult(email = email, breaches = breaches.toList(), checked = checked)

    // ── Severity ────────────────────────────────────────────────────────────────────────────

    @Test
    fun `readable passwords are critical, strongly hashed ones are high`() {
        assertEquals(BreachSeverity.CRITICAL, BreachAdvisor.severityOf(breach("A", "Email addresses", "Passwords", storage = PasswordStorage.PLAINTEXT)))
        assertEquals(BreachSeverity.CRITICAL, BreachAdvisor.severityOf(breach("A", "Email addresses", "Passwords", storage = PasswordStorage.EASY_TO_CRACK)))
        assertEquals(BreachSeverity.HIGH, BreachAdvisor.severityOf(breach("A", "Email addresses", "Passwords", storage = PasswordStorage.HARD_TO_CRACK)))
        assertEquals(BreachSeverity.HIGH, BreachAdvisor.severityOf(breach("A", "Email addresses", "Passwords")))
    }

    @Test
    fun `financial, identity and account-recovery data are critical`() {
        assertEquals(BreachSeverity.CRITICAL, BreachAdvisor.severityOf(breach("A", "Email addresses", "Partial credit card data")))
        assertEquals(BreachSeverity.CRITICAL, BreachAdvisor.severityOf(breach("A", "Email addresses", "Government issued IDs")))
        assertEquals(BreachSeverity.CRITICAL, BreachAdvisor.severityOf(breach("A", "Email addresses", "Social security numbers")))
        assertEquals(BreachSeverity.CRITICAL, BreachAdvisor.severityOf(breach("A", "Email addresses", "Security questions and answers")))
        assertEquals(BreachSeverity.CRITICAL, BreachAdvisor.severityOf(breach("A", "Email addresses", "Auth tokens")))
    }

    @Test
    fun `contact and personal details scale from medium to high`() {
        assertEquals(BreachSeverity.HIGH, BreachAdvisor.severityOf(breach("A", "Email addresses", "Phone numbers", "Dates of birth")))
        assertEquals(BreachSeverity.MEDIUM, BreachAdvisor.severityOf(breach("A", "Email addresses", "Phone numbers")))
        assertEquals(BreachSeverity.MEDIUM, BreachAdvisor.severityOf(breach("A", "Email addresses", "Physical addresses")))
        assertEquals(BreachSeverity.HIGH, BreachAdvisor.severityOf(breach("A", "Email addresses", "Private messages")))
    }

    @Test
    fun `email plus names and IP addresses is low`() {
        assertEquals(BreachSeverity.LOW, BreachAdvisor.severityOf(breach("A", "Email addresses", "Names", "IP addresses", "Geographic locations")))
    }

    // ── Recommendations ─────────────────────────────────────────────────────────────────────

    @Test
    fun `a low-impact breach is not told to call the bank or change passwords`() {
        val actions = BreachAdvisor.actionsFor(breach("Forum", "Email addresses", "Names", "IP addresses")).joinToString(" ")
        assertFalse(actions.contains("bank", ignoreCase = true))
        assertFalse(actions.contains("SIM", ignoreCase = true))
        assertFalse(actions.contains("password", ignoreCase = true))
        assertTrue("every breach warns about phishing that cites it", actions.contains("phishing") && actions.contains("Forum"))
    }

    @Test
    fun `each exposed data type brings its own specific step`() {
        val actions = BreachAdvisor.actionsFor(
            breach("Shop", "Email addresses", "Passwords", "Phone numbers", "Credit cards", "Dates of birth", storage = PasswordStorage.PLAINTEXT),
        ).joinToString(" ")
        assertTrue(actions.contains("Change your Shop password now"))
        assertTrue(actions.contains("treat the old one as public"))
        assertTrue(actions.contains("two-step verification"))
        assertTrue(actions.contains("SIM"))
        assertTrue(actions.contains("card issuer"))
        assertTrue(actions.contains("date of birth"))
    }

    @Test
    fun `leaked transaction records are high and don't ask for a replacement card`() {
        val b = breach("Ameriprise", "Email addresses", "Financial transactions", "Phone numbers")
        assertEquals(BreachSeverity.HIGH, BreachAdvisor.severityOf(b))
        val actions = BreachAdvisor.actionsFor(b).joinToString(" ")
        assertFalse(actions.contains("replacement card"))
        assertTrue(actions.contains("never ask for your password"))
    }

    // ── Aggregates: stealer logs and combo lists (real descriptions from XposedOrNot) ──────

    private fun described(name: String, description: String, vararg dataClasses: String) =
        breach(name, *dataClasses).copy(description = description)

    private val alien = described(
        "AlienStealerLogs",
        "ALIEN TXTBASE, a stealer log collection, was exposed in February 2025 when 23 billion rows of logs were obtained from a Telegram channel, revealing 299M unique email addresses along with the websites they were entered into and the passwords used.",
        "Email addresses", "Passwords",
    )
    private val pemiblanc = described(
        "Pemiblanc",
        "A credential stuffing list known as \"Pemiblanc\" containing 111 million email addresses was discovered in 2018.",
        "Email addresses", "Passwords",
    )
    private val antiPublic = described(
        "AntiPublicCombo",
        "The Anti Public Combo List, an aggregate collection consisting of data from various breaches, was made public on 2016.",
        "Email addresses", "Passwords",
    )
    private val joyGames = described(
        "JoyGames",
        "The JoyGames forum experienced a data breach in December 2019, exposing 4.5 million unique email addresses, usernames, IP addresses and passwords.",
        "Email addresses", "Usernames", "IP addresses", "Passwords",
    )

    @Test
    fun `stealer logs and combo lists are recognised from their descriptions`() {
        assertEquals(BreachKind.STEALER_LOGS, BreachAdvisor.kindOf(alien))
        assertEquals(BreachKind.COMBO_LIST, BreachAdvisor.kindOf(pemiblanc))
        assertEquals(BreachKind.COMBO_LIST, BreachAdvisor.kindOf(antiPublic))
        assertEquals("an ordinary site breach stays a site", BreachKind.SITE, BreachAdvisor.kindOf(joyGames))
    }

    @Test
    fun `aggregates never tell you to change a password on a site that doesn't exist`() {
        val stealer = BreachAdvisor.actionsFor(alien).joinToString(" ")
        assertFalse(stealer.contains("AlienStealerLogs"))
        assertTrue(stealer.contains("malware"))
        assertEquals(BreachSeverity.CRITICAL, BreachAdvisor.severityOf(alien))

        val combo = BreachAdvisor.actionsFor(pemiblanc).joinToString(" ")
        assertFalse(combo.contains("Pemiblanc"))
        assertTrue(combo.contains("compiled from many earlier breaches"))
    }

    @Test
    fun `password actions come first`() {
        val actions = BreachAdvisor.actionsFor(breach("Shop", "Email addresses", "Phone numbers", "Passwords"))
        assertTrue(actions.first().startsWith("Change your Shop password"))
    }

    @Test
    fun `password storage is only described when passwords actually leaked`() {
        assertNull(BreachAdvisor.passwordStorageNote(breach("A", "Email addresses", "Names", storage = PasswordStorage.PLAINTEXT)))
        assertTrue(BreachAdvisor.passwordStorageNote(breach("A", "Passwords", storage = PasswordStorage.PLAINTEXT))!!.contains("plain text"))
        assertTrue(BreachAdvisor.passwordStorageNote(breach("A", "Passwords", storage = PasswordStorage.HARD_TO_CRACK))!!.contains("strong hashing"))
    }

    // ── New vs already known ────────────────────────────────────────────────────────────────

    @Test
    fun `a failed check never alerts`() {
        assertEquals(BreachAlertPlan.None, BreachAdvisor.plan(result("a@x.com", breach("A"), checked = false), null))
    }

    @Test
    fun `first check of an address is a single first-look summary`() {
        val plan = BreachAdvisor.plan(result("a@x.com", breach("A"), breach("B")), null)
        assertTrue(plan is BreachAlertPlan.FirstLook)
        assertEquals(2, (plan as BreachAlertPlan.FirstLook).breaches.size)
    }

    @Test
    fun `first check of a clean address says nothing`() {
        assertEquals(BreachAlertPlan.None, BreachAdvisor.plan(result("a@x.com"), null))
    }

    @Test
    fun `only breaches missing from the baseline are new`() {
        val baseline = StoredBreachMonitor("a@x.com", listOf("A", "B"), lastCheckedAtMs = 1L)
        assertEquals(BreachAlertPlan.None, BreachAdvisor.plan(result("a@x.com", breach("A"), breach("B")), baseline))

        val plan = BreachAdvisor.plan(result("a@x.com", breach("A"), breach("B"), breach("C")), baseline)
        assertTrue(plan is BreachAlertPlan.NewBreaches)
        assertEquals(listOf("C"), (plan as BreachAlertPlan.NewBreaches).breaches.map { it.name })
    }

    @Test
    fun `baseline email matching ignores case`() {
        val baseline = StoredBreachMonitor("Alice@Example.com", listOf("A"), lastCheckedAtMs = 1L)
        assertEquals(BreachAlertPlan.None, BreachAdvisor.plan(result("alice@example.com", breach("A")), baseline))
    }

    @Test
    fun `a baseline for a different address is not inherited`() {
        val someoneElse = StoredBreachMonitor("other@x.com", listOf("A"), lastCheckedAtMs = 1L)
        assertTrue(BreachAdvisor.plan(result("a@x.com", breach("A")), someoneElse) is BreachAlertPlan.FirstLook)
    }

    @Test
    fun `baseline keeps breaches that briefly drop out of the results`() {
        val previous = StoredBreachMonitor("a@x.com", listOf("A", "B"), lastCheckedAtMs = 1L)
        val updated = BreachAdvisor.updatedBaseline(result("a@x.com", breach("A"), breach("C")), previous, nowMs = 99L)
        assertEquals(setOf("A", "B", "C"), updated.knownBreachNames.toSet())
        assertEquals(99L, updated.lastCheckedAtMs)
        // So "B" coming back later is not announced as new.
        val plan = BreachAdvisor.plan(result("a@x.com", breach("A"), breach("B"), breach("C")), updated)
        assertEquals(BreachAlertPlan.None, plan)
    }

    @Test
    fun `baseline for a new address starts fresh`() {
        val previous = StoredBreachMonitor("other@x.com", listOf("Z"), lastCheckedAtMs = 1L)
        val updated = BreachAdvisor.updatedBaseline(result("a@x.com", breach("A")), previous, nowMs = 2L)
        assertEquals("a@x.com", updated.email)
        assertEquals(listOf("A"), updated.knownBreachNames)
    }

    // ── Ordering, persistence mapping, formatting ───────────────────────────────────────────

    @Test
    fun `most severe first, then most recently added`() {
        val low = breach("Low", "Email addresses", addedAt = "2026-09-01T00:00:00+00:00")
        val criticalOld = breach("CritOld", "Passwords", storage = PasswordStorage.PLAINTEXT, addedAt = "2020-01-01T00:00:00+00:00")
        val criticalNew = breach("CritNew", "Passwords", storage = PasswordStorage.PLAINTEXT, addedAt = "2026-01-01T00:00:00+00:00")
        val medium = breach("Med", "Phone numbers")
        assertEquals(listOf("CritNew", "CritOld", "Med", "Low"), BreachAdvisor.prioritized(listOf(low, criticalOld, medium, criticalNew)).map { it.name })
    }

    @Test
    fun `stored breach round-trips without losing anything`() {
        val original = BreachRecord(
            name = "Shop", date = "2025", dataClasses = listOf("Email addresses", "Passwords"), records = 1234L,
            domain = "shop.example", description = "desc", industry = "Retail", passwordStorage = PasswordStorage.EASY_TO_CRACK,
            verified = true, addedAt = "2026-01-02T03:04:05+00:00", referenceUrl = "https://example.com/report",
        )
        assertEquals(original, original.toStored().toRecord())
    }

    @Test
    fun `record counts are compact and locale-independent`() {
        assertEquals("950", BreachAdvisor.compactCount(950))
        assertEquals("84K", BreachAdvisor.compactCount(84_321))
        assertEquals("8.4M", BreachAdvisor.compactCount(8_379_476))
        assertEquals("3M", BreachAdvisor.compactCount(3_000_000))
        assertEquals("1.2B", BreachAdvisor.compactCount(1_200_000_000))
    }

    // ── Wire format ─────────────────────────────────────────────────────────────────────────

    /** An excerpt of a real XposedOrNot breach-analytics response, so a renamed field fails here. */
    @Test
    fun `real breach-analytics response decodes every field the app uses`() {
        val raw = """
            {"BreachMetrics":{"risk":[{"risk_label":"Critical","risk_score":100}],"industry":[[["misc",15]]]},
             "PastesSummary":{"cnt":2,"domain":"","tmpstmp":""},
             "ExposedBreaches":{"breaches_details":[{
               "breach":"AlienStealerLogs",
               "details":"ALIEN TXTBASE, a stealer log collection, was exposed in February 2025.",
               "domain":"","industry":"Miscellaneous",
               "logo":"https://xposedornot.com/static/logos/combolist.png",
               "password_risk":"plaintext","references":"","searchable":"Yes","verified":"Yes",
               "xposed_data":"Email addresses;Passwords","xposed_date":"2025",
               "xposed_records":299646818,"added":"2026-06-15T05:23:45+00:00"}]}}
        """.trimIndent()
        val json = Json { ignoreUnknownKeys = true; coerceInputValues = true; explicitNulls = false }
        val decoded = json.decodeFromString(XonAnalyticsResponse.serializer(), raw)
        val d = decoded.exposedBreaches!!.breachesDetails!!.single()
        assertEquals("AlienStealerLogs", d.breach)
        assertEquals("plaintext", d.passwordRisk)
        assertEquals("Email addresses;Passwords", d.xposedData)
        assertEquals(299_646_818L, d.xposedRecords)
        assertEquals("Yes", d.verified)
        assertEquals("2026-06-15T05:23:45+00:00", d.added)
        assertEquals("Critical", decoded.breachMetrics!!.risk!!.single().riskLabel)
        assertEquals(100, decoded.breachMetrics!!.risk!!.single().riskScore)
        assertEquals(2, decoded.pastesSummary!!.cnt)
    }

    @Test
    fun `clean-address response decodes to no breaches`() {
        val raw = """{"BreachMetrics":null,"BreachesSummary":{"site":""},"ExposedBreaches":null,"ExposedPastes":null,"PasteMetrics":null,"PastesSummary":{"cnt":0,"domain":"","tmpstmp":""}}"""
        val json = Json { ignoreUnknownKeys = true; coerceInputValues = true; explicitNulls = false }
        val decoded = json.decodeFromString(XonAnalyticsResponse.serializer(), raw)
        assertNull(decoded.exposedBreaches)
        assertEquals(0, decoded.pastesSummary!!.cnt)
    }
}
