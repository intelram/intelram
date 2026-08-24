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
        Assurance("Certified", "ISO 27001 and SOC 2 Type II. GDPR compliant, data stored in the EU."),
        Assurance("You can see the reasoning", "Every alert names the sources that flagged it and how sure the AI is."),
        Assurance("Never sells your data", "No advertising SDKs. Independently verified in the annual audit."),
    )

    val brainStats = listOf(
        Stat("6", "intelligence feeds live"),
        Stat("90s", "between updates"),
        Stat("24h", "human review promise"),
    )

    val qrSamples = listOf(
        QrSample(
            label = "Cafe Wi‑Fi poster", url = "wifi:CoffeeShop_Free", verdict = QrVerdict.SAFE,
            title = "Safe to open",
            detail = "Wi‑Fi join request for an open network. No malicious payload, but the network is unencrypted.",
            feed = "AbuseIPDB · clean", action = "Join network",
        ),
        QrSample(
            label = "Parking meter sticker", url = "pay-parkzone[.]app/qr/8812", verdict = QrVerdict.DANGER,
            title = "Malicious — blocked",
            detail = "Quishing campaign. Sticker overlays the real meter code and leads to a cloned payment page that harvests card details.",
            feed = "OpenPhish · reported 4h ago", action = "Report & discard",
        ),
        QrSample(
            label = "Delivery slip", url = "track-parcel-verify[.]top/pkg", verdict = QrVerdict.WARN,
            title = "Suspicious — proceed with care",
            detail = "Domain registered 2 days ago and mimics a courier brand. Requests an \"unpaid fee\" before showing tracking.",
            feed = "Safe Browsing · low reputation", action = "Block domain",
        ),
        QrSample(
            label = "Restaurant menu", url = "menu.bistronova.com/t/14", verdict = QrVerdict.SAFE,
            title = "Safe to open",
            detail = "Verified HTTPS site with a 3‑year history and no reports across connected feeds.",
            feed = "Safe Browsing · clean", action = "Open link",
        ),
    )

    val phases = listOf(
        ScanPhase("Building software inventory…", "214 apps · 38 system packages"),
        ScanPhase("Scanning apps & sideloaded APKs…", "Signature + behavioral match"),
        ScanPhase("Auditing background services & activities…", "61 services running"),
        ScanPhase("Checking versions & license status…", "CVE + license registry"),
        ScanPhase("Checking connected hardware…", "USB, Bluetooth, SIM, chargers, card slot"),
        ScanPhase("Probing open ports & listeners…", "65,535 ports on this device"),
        ScanPhase("Verifying OS build & patch level…", "Android patch registry"),
        ScanPhase("Analyzing links, NFC & payment handlers…", "Live phishing feeds"),
        ScanPhase("Checking your email in breach databases…", "14B leaked records · Have I Been Pwned"),
    )

    val masterThreats = listOf(
        Finding(
            id = "t1", name = "Sideloaded app \"GenAI Booster\"", type = "Malware · Sideloaded APK",
            cat = Category.SOFTWARE, sev = Severity.CRITICAL, risk = 96,
            desc = "This APK was installed outside an official store and matches a ClayRat‑family trojan. It promises free AI tools while reading chat histories and silently using the front camera.",
            advice = "Uninstall this app immediately and avoid sideloaded APKs. Thread Protection has already blocked its background network access.",
            fix = "Uninstall app",
            pros = listOf(
                "Stops active data exfiltration immediately",
                "Frees ~340 MB and cuts battery drain",
                "Removes camera and microphone access",
            ),
            cons = listOf(
                "Any content saved only inside the app is lost",
                "You lose the AI features it advertised",
            ),
            source = "Play Protect + VirusTotal · 92 engines",
        ),
        Finding(
            id = "t2", name = "Port 5555 open — ADB over Wi‑Fi", type = "Open port · Listening on 0.0.0.0",
            cat = Category.PORTS, sev = Severity.CRITICAL, risk = 92,
            desc = "Wireless debugging left port 5555 listening on every network interface. Anyone on the same Wi‑Fi can install apps, read storage and run shell commands without unlocking your phone. This is the single most exploited misconfiguration on Android.",
            advice = "Turn off wireless debugging now and only enable it on trusted networks while actively developing.",
            fix = "Close port 5555",
            pros = listOf(
                "Removes full remote shell access to your device",
                "No functional loss for everyday use",
            ),
            cons = listOf(
                "Wireless ADB debugging stops working until re‑enabled",
                "Wired USB debugging is unaffected",
            ),
            source = "Local port scan + AbuseIPDB exposure check",
        ),
        Finding(
            id = "t3", name = "Tap‑to‑pay hijack attempt", type = "Service · NFC payment handler",
            cat = Category.SERVICES, sev = Severity.HIGH, risk = 84,
            desc = "A background service tried to register itself as your default contactless payment handler — the pattern used by NFC relay malware to route card data to attacker devices and ATMs.",
            advice = "Restore your trusted wallet as the default payment app and remove the requesting app. Keep NFC off when not paying.",
            fix = "Restore trusted wallet",
            pros = listOf(
                "Prevents card relay fraud at terminals and ATMs",
                "Restores your verified wallet as default",
            ),
            cons = listOf(
                "The other app can no longer be used to pay",
                "You must re‑confirm your wallet PIN once",
            ),
            source = "Behavioral engine · NFC handler watch",
        ),
        Finding(
            id = "t4", name = "Outdated browser — 3 known CVEs", type = "Outdated software · v118 (current v141)",
            cat = Category.SOFTWARE, sev = Severity.HIGH, risk = 78,
            desc = "Your browser is 23 versions behind and exposed to three publicly documented flaws, including a rendering‑engine bug that allows drive‑by code execution from a single malicious page.",
            advice = "Update the browser now. Enable automatic app updates so version gaps do not reopen.",
            fix = "Update browser",
            pros = listOf(
                "Patches 3 known remote‑code‑execution flaws",
                "Faster page loads and lower memory use",
            ),
            cons = listOf(
                "~180 MB download over your connection",
                "Extensions may need to be re‑authorised",
            ),
            source = "CVE database · NVD feed",
        ),
        Finding(
            id = "t5", name = "Unlicensed app — \"OfficeSuite Pro (Mod)\"", type = "Licensing · Cracked build",
            cat = Category.LICENSING, sev = Severity.HIGH, risk = 74,
            desc = "This is a modified build with its licence check patched out. Cracked packages are the most common delivery route for bundled spyware, and they never receive vendor security updates.",
            advice = "Remove the modified build and install the official version, free tier or licensed.",
            fix = "Remove unlicensed app",
            pros = listOf(
                "Eliminates a known malware delivery route",
                "Restores a legal, updatable install path",
                "Removes legal exposure for business use",
            ),
            cons = listOf(
                "Paid features require a real licence",
                "Local documents should be exported first",
            ),
            source = "Licence registry + VirusTotal",
        ),
        Finding(
            id = "t6", name = "AI‑crafted phishing text", type = "Phishing · SMS \"unpaid customs fee\"",
            cat = Category.ACTIVITY, sev = Severity.MEDIUM, risk = 62,
            desc = "A message in your inbox links to a fake courier payment page. It was generated with AI — flawless language and a spoofed sender make it hard to spot by eye.",
            advice = "Do not open the link. Block the sender and report the message; never enter card details from an SMS link.",
            fix = "Block sender & delete",
            pros = listOf(
                "Blocks the sender and any repeat attempts",
                "Feeds the report back to live phishing databases",
            ),
            cons = listOf("Legitimate messages from that number are also blocked"),
            source = "OpenPhish + Safe Browsing · live feed",
        ),
        Finding(
            id = "t7", name = "Background service sending data hourly", type = "Ongoing activity · \"SysHelper\" 4.2 MB/day",
            cat = Category.ACTIVITY, sev = Severity.MEDIUM, risk = 58,
            desc = "A service with no visible app icon wakes every hour and uploads to a host flagged for ad‑fraud traffic. It has run in the background for 19 days.",
            advice = "Restrict its background data and network access, then uninstall the parent package if you do not recognise it.",
            fix = "Restrict background data",
            pros = listOf(
                "Stops silent uploads and unexplained data use",
                "Noticeably better standby battery life",
            ),
            cons = listOf("If it belongs to a tool you use, its sync will stop"),
            source = "Traffic analyzer + AbuseIPDB",
        ),
        Finding(
            id = "t9", name = "USB keyboard typing by itself", type = "Hardware · OTG device on charging port",
            cat = Category.HARDWARE, sev = Severity.CRITICAL, risk = 94,
            desc = "A device connected to your charging port identifies itself as a keyboard, then sent 40 keystrokes with no one touching it. This is a BadUSB keylogger — it types commands to install spyware and record everything you enter, including passwords.",
            advice = "Unplug it now. Do not reuse that cable, dock or public charger. Nothing was installed — we blocked its input.",
            fix = "Block this device",
            pros = listOf(
                "Stops keystroke capture of passwords and banking codes",
                "Blocks the device from installing anything",
                "Remembers this device and blocks it in future",
            ),
            cons = listOf("A genuine keyboard you own would need to be allowed again"),
            source = "USB input watchdog + NGFW device profiles",
        ),
        Finding(
            id = "t8", name = "Security patch 5 months old", type = "Operating system · Patch level",
            cat = Category.OS, sev = Severity.LOW, risk = 44,
            desc = "Your Android security patch level is outdated. Unpatched devices are prime targets for remote access trojans, keyloggers and zero‑day exploits.",
            advice = "Install the latest system update. If your device no longer receives updates, rely on real‑time behavioral protection.",
            fix = "Open system update",
            pros = listOf(
                "Closes 41 patched kernel and framework flaws",
                "Usually improves battery and stability",
            ),
            cons = listOf(
                "Requires a restart and ~15 minutes",
                "Some older apps may need updating afterwards",
            ),
            source = "Android patch registry",
        ),
    )

    val breachSites = listOf(
        Breach("SocialHub", "Jan 2026", "Email, password hash, phone"),
        Breach("ShopFast", "Aug 2025", "Email, name, home address"),
        Breach("FitTrack", "Mar 2024", "Email, password hash, date of birth"),
        Breach("Collection #7 combo list", "Nov 2023", "Email + plaintext password"),
    )

    fun emailFinding(email: String): Finding {
        val n = breachSites.size
        return Finding(
            id = "tb", name = email, type = "Email address · found in $n data breaches",
            cat = Category.EMAIL, sev = Severity.HIGH, risk = 81,
            desc = "We checked the email you signed in with against 14 billion leaked records. It appears in $n known breaches. One leak included a password in plain text, which is how attackers get into other accounts that reuse it.",
            advice = "Change the password anywhere you reused it, starting with your email and bank. Turn on two‑step verification on your Google account.",
            fix = "Start password check-up",
            pros = listOf(
                "Shows exactly which sites leaked your details",
                "Stops one old password unlocking your other accounts",
                "You get an alert the moment your email appears again",
            ),
            cons = listOf(
                "You will need to change several passwords",
                "Takes about 10 minutes the first time",
            ),
            source = "Have I Been Pwned · 14B leaked records",
            breaches = breachSites,
        )
    }

    val appPerms = listOf(
        PermApp(
            "ChatNow", "Messaging · Play Store",
            listOf(
                AppPermission("sms", "Read your text messages", "Not used in 90 days", risk = true),
                AppPermission("contacts", "Your contacts", "Used yesterday", risk = false),
                AppPermission("mic", "Microphone", "Used for voice notes", risk = false),
                AppPermission("loc", "Location, all the time", "Not needed for messaging", risk = true),
            ),
        ),
        PermApp(
            "GenAI Booster", "Sideloaded · flagged as malware",
            listOf(
                AppPermission("acc", "Control your screen (accessibility)", "Can read everything you type", risk = true),
                AppPermission("cam", "Camera", "Used 41 times in the background", risk = true),
                AppPermission("files", "All your files", "Copied 212 files last week", risk = true),
            ),
        ),
        PermApp(
            "SysHelper", "Background service · no app icon",
            listOf(
                AppPermission("bg", "Run in the background", "Wakes every hour", risk = true),
                AppPermission("net", "Unrestricted internet", "Uploads 4.2 MB a day", risk = true),
            ),
        ),
        PermApp(
            "ShopFast", "Shopping · Play Store",
            listOf(
                AppPermission("loc", "Location while using the app", "For delivery addresses", risk = false),
                AppPermission("notif", "Send you notifications", "Mostly adverts", risk = true),
            ),
        ),
        PermApp(
            "Camera", "System app · built in",
            listOf(
                AppPermission("cam", "Camera", "Needed to take photos", risk = false),
                AppPermission("mic", "Microphone", "Needed for video sound", risk = false),
                AppPermission("loc", "Location while using the app", "Adds place to photos", risk = false),
            ),
        ),
        PermApp(
            "OfficeSuite Pro (Mod)", "Unlicensed copy",
            listOf(
                AppPermission("files", "All your files", "Needed to open documents", risk = false),
                AppPermission("sms", "Read your text messages", "No reason for a document app", risk = true),
            ),
        ),
    )

    val hwDevices = listOf(
        HwDevice("USB-C charger", "Power only · no data lines active", ok = true),
        HwDevice("Bluetooth earbuds", "Paired 8 months · audio only", ok = true),
        HwDevice("SIM card", "Same SIM since setup · not swapped", ok = true),
        HwDevice("USB keyboard (OTG)", "Connected 3 min ago · asking for input access", ok = false),
        HwDevice("Camera & microphone", "No app using them right now", ok = true),
        HwDevice("Memory card", "None inserted", ok = true),
    )

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
