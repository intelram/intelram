package com.threadprotection.app.network

/**
 * Turns everything gathered about a link into one verdict.
 *
 * **Root cause this replaces.** The old rule was a cliff:
 * `suspiciousCount > 0 || worstHeuristic >= 1 -> SUSPICIOUS`. One low-severity note — a `.xyz`
 * ending, four hyphens, plain HTTP — condemned a site outright, and *no* amount of positive
 * evidence could pull it back: a twenty-year-old domain with a valid certificate and a clean
 * record on every blocklist queried still read SUSPICIOUS. Combined with the brand-matching bug in
 * [BrandRegistry]'s doc, that made essentially every site suspicious, including `google.com`.
 *
 * Evidence is now weighed both ways:
 * - Something that only exists to deceive, or a dedicated blocklist naming this exact URL, is
 *   decisive on its own ([SignalWeight.DEFINITIVE]).
 * - Everything else contributes points, and genuine positive evidence — an established
 *   registration, a trusted certificate, a clean security-resolver answer — subtracts them.
 * - Positive credit from a certificate or DNSSEC is **suppressed** when a severity-3 deception
 *   flag fired: a valid certificate proves control of a domain, not honesty, and is free, so a
 *   brand-impersonating look-alike must not be able to buy its way back to safe with one.
 */
object UrlVerdictScoring {

    /** Net score at or above which a link is called malicious. */
    private const val MALICIOUS_THRESHOLD = 6

    /** Net score at or above which a link is called suspicious. */
    private const val SUSPICIOUS_THRESHOLD = 2

    /** The severity at which a heuristic describes a technique that only exists to deceive. */
    private const val DECEPTION_SEVERITY = 3

    data class Input(
        val signals: List<UrlSignal>,
        val flags: List<UrlHeuristics.Flag>,
        /** Whether anything at all was learned about the host — false means "we truly don't know". */
        val hasAnyEvidence: Boolean,
    )

    data class Outcome(val verdict: Verdict, val riskPoints: Int, val trustPoints: Int, val confidence: Int)

    fun evaluate(input: Input): Outcome {
        val deceptive = input.flags.any { it.severity >= DECEPTION_SEVERITY }

        // A blocklist naming this exact resource, or a certificate that fails validation, ends it.
        val decisive = input.signals.any { it.verdict == Verdict.MALICIOUS && it.weight == SignalWeight.DEFINITIVE }

        var risk = 0
        var trust = 0
        for (signal in input.signals) {
            when (signal.verdict) {
                Verdict.MALICIOUS -> risk += when (signal.weight) {
                    SignalWeight.DEFINITIVE -> 8
                    SignalWeight.STRONG -> 4
                    SignalWeight.SUPPORTING -> 2
                }
                Verdict.SUSPICIOUS -> risk += when (signal.weight) {
                    SignalWeight.DEFINITIVE -> 4
                    SignalWeight.STRONG -> 2
                    SignalWeight.SUPPORTING -> 1
                }
                Verdict.SAFE -> {
                    // See the class doc: a free certificate on a look-alike domain is not
                    // reassurance, so deception suppresses the softer forms of positive credit.
                    val credit = when (signal.weight) {
                        SignalWeight.DEFINITIVE -> 3
                        SignalWeight.STRONG -> 2
                        SignalWeight.SUPPORTING -> 1
                    }
                    trust += if (deceptive && signal.weight != SignalWeight.DEFINITIVE) 0 else credit
                }
                Verdict.UNKNOWN -> Unit
            }
        }

        for (flag in input.flags) {
            risk += when {
                flag.severity >= DECEPTION_SEVERITY -> 5
                flag.severity == 2 -> 2
                else -> 1
            }
        }

        val net = risk - trust
        val verdict = when {
            decisive -> Verdict.MALICIOUS
            net >= MALICIOUS_THRESHOLD -> Verdict.MALICIOUS
            net >= SUSPICIOUS_THRESHOLD -> Verdict.SUSPICIOUS
            input.hasAnyEvidence -> Verdict.SAFE
            else -> Verdict.UNKNOWN
        }

        // Confidence tracks how much was actually established, not how bad the answer is.
        val confidence = when {
            !input.hasAnyEvidence -> 35
            decisive -> 99
            else -> (50 + input.signals.size * 7 + input.flags.size * 3).coerceIn(40, 97)
        }

        return Outcome(verdict, risk, trust, confidence)
    }
}
