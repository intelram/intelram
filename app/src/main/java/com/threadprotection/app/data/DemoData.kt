package com.threadprotection.app.data

import com.threadprotection.app.ui.theme.Severity

/**
 * Demo data ported verbatim from the prototype's `MASTER_THREATS`, `HW_SIM`, `emailFinding()`
 * and related arrays (see handoff README). Every number and line of copy here is demo data —
 * see README §Backend requirements for what a real integration needs to wire in.
 */
object DemoData {

    val ticker = listOf(
        "Blocked a fake bank link · 2 min ago",
        "Stopped a cloned payment page · 4 min ago",
        "Removed a hidden tracking app · 6 min ago",
        "Caught a scam delivery text · 9 min ago",
    )

    val stats = listOf(
        Stat("4.8★", "312,000 ratings"),
        Stat("12M+", "people protected"),
        Stat("6", "live threat feeds"),
    )

    val trust = listOf(
        TrustPoint(
            "We never read your photos or messages",
            "Checks happen on your phone. Your private content never leaves it.",
        ),
        TrustPoint(
            "No ads. Nothing sold. Ever.",
            "We make money from subscriptions only — never from your data.",
        ),
        TrustPoint(
            "Plain words, no jargon",
            "Every warning explains what it means and what to do next.",
        ),
    )

    val obFeatures = listOf(
        "Finds harmful apps and hidden programs",
        "Warns you about fake messages and scam links",
        "Checks your Wi‑Fi, updates and passwords",
    )

    val feeds = listOf(
        Feed("Google Play Protect", "Malware signatures", "1.4M"),
        Feed("Safe Browsing", "Phishing & malicious URLs", "3.9M"),
        Feed("VirusTotal", "Multi-engine file verdicts", "92 engines"),
        Feed("PhishTank / OpenPhish", "Live phishing reports", "61K today"),
        Feed("AbuseIPDB", "Malicious hosts & C2", "812K IPs"),
        Feed("Have I Been Pwned", "Breach corpus", "14B records"),
    )
    const val feedSyncLine = "All 6 feeds synced · last update 2 min ago"

    val brainSources = listOf(
        BrainSource(
            "Threat intelligence feeds",
            "6 feeds · Play Protect, Safe Browsing, VirusTotal, OpenPhish, AbuseIPDB, HIBP",
            "syncing every 90s",
        ),
        BrainSource(
            "On-device AI model",
            "Learns how your phone normally behaves, then flags what breaks the pattern",
            "v41 · updated today",
        ),
        BrainSource(
            "Cloud AI analysis",
            "Unknown files and links are reasoned about, not just matched to a list",
            "2.1s average verdict",
        ),
        BrainSource(
            "Next-generation firewall",
            "Deep packet inspection and app-aware rules from partnered NGFW telemetry",
            "412 blocked hosts",
        ),
        BrainSource(
            "Human security analysts",
            "Every disputed detection is reviewed by a person within 24 hours",
            "38 reviews today",
        ),
        BrainSource(
            "People like you",
            "Your \"right / wrong\" taps teach the model what real users actually see",
            "1.9M reports this month",
        ),
    )

    val learnLoop = listOf(
        LearnStep("Collect", "Signals arrive from feeds, the firewall, your device and other users."),
        LearnStep("Reason", "AI weighs them together instead of trusting any single list."),
        LearnStep("Verify", "Anything uncertain goes to a human analyst before it alarms you."),
        LearnStep("Improve", "The confirmed answer trains the model — usually within the hour."),
    )

    val assurances = listOf(
        Assurance("Independently audited", "Full source and infrastructure audit by Cure53, published every year."),
        Assurance("You can see the reasoning", "Every alert names the sources that flagged it and how sure the AI is."),
        Assurance("Never sells your data", "No advertising SDKs. Independently verified in the annual audit."),
    )

    val brainStats = listOf(
        Stat("6", "intelligence feeds live"),
        Stat("90s", "between updates"),
        Stat("24h", "human review promise"),
    )

    /** Seed URLs for the QR screen's "try a code" grid — each runs through the real live checker (§ThreatIntelRepository). */
    val qrSamples = listOf(
        QrSample("Cafe Wi‑Fi poster", "wifi:CoffeeShop_Free"),
        QrSample("Suspicious parking sticker", "http://pay-parkzone-secure.top/qr/8812"),
        QrSample("Delivery tracking link", "http://track-parcel-verify.top/pkg"),
        QrSample("Official Google page", "https://www.google.com/"),
    )

    /** Canned scenarios for the dashboard's explicitly-labelled "Show me what an alert looks like" demo button. */
    val hwSim = listOf(
        HwSim(
            "Unknown USB keyboard", "Plugged into the charging port", HwVerdict.DANGER, 94,
            "This device says it is a keyboard, but it started typing commands by itself the moment it connected. That is how a keylogger installs itself.",
            "Unplug it and do not use this cable or dock again.",
        ),
        HwSim(
            "Public charging dock", "USB data lines active", HwVerdict.WARN, 71,
            "This charger is asking for data access, not just power. Public chargers are used to copy files off phones — known as juice jacking.",
            "Allow charging only. Block data.",
        ),
        HwSim(
            "Bluetooth device \"JBL-9F2A\"", "Pairing request nearby", HwVerdict.WARN, 66,
            "An unknown device is asking to pair and requesting access to your contacts and messages, which speakers never need.",
            "Refuse the pairing unless you recognise the device.",
        ),
    )

    /** Sample "signed in" account used by the demo sign-in / skip flow, matching the prototype's mock account. */
    val demoAccount = Account(name = "Margaret Doyle", email = "margaret.doyle@gmail.com", initial = "M")
}
