package com.intelram.netsteer.core

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException

/** Decides whether a host name should be blocked. */
fun interface HostFilter {
    fun blocks(host: String): Boolean
}

/**
 * Domain blocklist in the style of AdGuard's DNS filtering.
 *
 * Understands the rule forms used by DNS filter lists (AdGuard DNS filter,
 * EasyList-derived host lists, hosts files):
 *  - `||example.com^`            block example.com and all subdomains
 *  - `@@||example.com^`          exception (never block)
 *  - `||example.com^$important`  block even if an exception matches
 *  - `||example.com^$badfilter`  cancels the same rule from another list
 *  - `0.0.0.0 example.com` / `127.0.0.1 example.com`   hosts-file format
 *  - `example.com` / `.example.com^`                    plain domain lists
 * Cosmetic (`##`), URL-path, regex (`/.../`) and wildcard rules can't be
 * applied at the network level without decrypting traffic and are skipped.
 *
 * Domains are stored as sorted 64-bit hashes (8 bytes each), so ~200k rules
 * take ~1.6 MB and a lookup is a few binary searches.
 */
class DomainFilter private constructor(
    private val blocked: LongArray,
    private val important: LongArray,
    private val allowed: LongArray,
) : HostFilter {
    val size: Int get() = blocked.size + important.size

    override fun blocks(host: String): Boolean {
        val h = normalize(host) ?: return false
        val hashes = suffixHashes(h)
        if (hashes.any { important.contains(it) }) return true
        if (hashes.any { allowed.contains(it) }) return false
        return hashes.any { blocked.contains(it) }
    }

    /** Returns a copy whose allowlist also contains [domains] (user exceptions). */
    fun withAllowed(domains: Collection<String>): DomainFilter {
        val extra = domains.mapNotNull { cleanDomain(it) }.map(::hash)
        return DomainFilter(blocked, important, (allowed.asList() + extra).toSortedUnique())
    }

    fun writeTo(out: DataOutputStream) {
        out.writeInt(MAGIC)
        for (arr in listOf(blocked, important, allowed)) {
            out.writeInt(arr.size)
            arr.forEach(out::writeLong)
        }
    }

    class Builder {
        private val blocked = HashSet<Long>()
        private val important = HashSet<Long>()
        private val allowed = HashSet<Long>()
        private val bad = HashSet<Long>()
        var skipped = 0
            private set

        fun addBlocked(domain: String) = cleanDomain(domain)?.let { blocked += hash(it); true } ?: false
        fun addAllowed(domain: String) = cleanDomain(domain)?.let { allowed += hash(it); true } ?: false

        /** Parse one filter list; returns how many rules were accepted. */
        fun addList(text: String): Int {
            var accepted = 0
            text.lineSequence().forEach { raw -> if (addRule(raw)) accepted++ }
            return accepted
        }

        fun addRule(raw: String): Boolean {
            var line = raw.trim()
            if (line.isEmpty() || line.startsWith("!") || line.startsWith("[")) return false
            if (line.startsWith("#")) return false
            if (line.contains("##") || line.contains("#@#") || line.contains("#?#") || line.contains("#$#")) return skip()

            // hosts file: "0.0.0.0 domain [# comment]"
            val hostsPrefix = HOSTS_PREFIXES.firstOrNull { line.startsWith(it) }
            if (hostsPrefix != null) {
                val name = line.substring(hostsPrefix.length).trim().substringBefore('#').trim().substringBefore(' ')
                if (name in HOSTS_IGNORE) return false
                return cleanDomain(name)?.let { blocked += hash(it); true } ?: skip()
            }

            val exception = line.startsWith("@@")
            if (exception) line = line.substring(2)
            var modifiers = emptyList<String>()
            val dollar = line.indexOf('$')
            if (dollar >= 0) {
                modifiers = line.substring(dollar + 1).split(',').map { it.trim().lowercase() }
                line = line.substring(0, dollar)
            }
            if (modifiers.any { it !in SUPPORTED_MODIFIERS }) return skip()
            if (line.startsWith("/") || line.contains('*')) return skip() // regex / wildcard

            line = line.removePrefix("||").removePrefix("|").removePrefix(".")
            line = line.removeSuffix("|").removeSuffix("^")
            val d = cleanDomain(line) ?: return skip()
            val h = hash(d)
            when {
                "badfilter" in modifiers -> bad += h
                exception -> allowed += h
                "important" in modifiers -> important += h
                else -> blocked += h
            }
            return true
        }

        fun build(): DomainFilter {
            blocked.removeAll(bad)
            important.removeAll(bad)
            return DomainFilter(blocked.toSortedUnique(), important.toSortedUnique(), allowed.toSortedUnique())
        }

        private fun skip(): Boolean { skipped++; return false }
    }

    companion object {
        private const val MAGIC = 0x4E534446 // "NSDF"
        private const val MAX_ENTRIES = 3_000_000
        private val HOSTS_PREFIXES = listOf("0.0.0.0 ", "0.0.0.0\t", "127.0.0.1 ", "127.0.0.1\t", ":: ", "::1 ", "::0 ")
        private val HOSTS_IGNORE = setOf("localhost", "localhost.localdomain", "local", "broadcasthost", "0.0.0.0", "ip6-localhost", "ip6-loopback")
        private val SUPPORTED_MODIFIERS = setOf("important", "badfilter", "third-party", "3p", "all", "document", "doc", "popup")

        val EMPTY = DomainFilter(LongArray(0), LongArray(0), LongArray(0))

        fun readFrom(input: DataInputStream): DomainFilter {
            if (input.readInt() != MAGIC) throw IOException("not a filter cache")
            fun arr(): LongArray {
                val n = input.readInt()
                if (n < 0 || n > MAX_ENTRIES) throw IOException("corrupt filter cache")
                val a = LongArray(n) { input.readLong() }
                for (i in 1 until n) if (a[i - 1] >= a[i]) throw IOException("unsorted filter cache")
                return a
            }
            return DomainFilter(arr(), arr(), arr())
        }

        /** Lowercase, strip a trailing dot; null for empty or IP-literal hosts. */
        fun normalize(host: String): String? {
            val h = host.trim().trimEnd('.').lowercase()
            if (h.isEmpty() || h.length > 253) return null
            return h
        }

        /** A valid multi-label domain name, or null. Single labels ("com") are rejected so a rule can't block a TLD. */
        fun cleanDomain(s: String): String? {
            val d = normalize(s) ?: return null
            if (!d.contains('.')) return null
            for (c in d) if (!(c in 'a'..'z' || c in '0'..'9' || c == '-' || c == '.' || c == '_')) return null
            if (d.split('.').any { it.isEmpty() || it.length > 63 }) return null
            if (d.all { it.isDigit() || it == '.' }) return null // IPv4 literal
            return d
        }

        /** example.co.uk -> hashes of [example.co.uk, co.uk] (single-label TLDs never match). */
        internal fun suffixHashes(host: String): List<Long> {
            val out = ArrayList<Long>(4)
            var start = 0
            while (true) {
                val rest = host.substring(start)
                if (!rest.contains('.')) break
                out += hash(rest)
                start = host.indexOf('.', start) + 1
                if (start <= 0) break
            }
            return out
        }

        /** FNV-1a 64 + murmur3 finalizer: fast, well-distributed; collisions ~1e-8 at 1M entries. */
        fun hash(s: String): Long {
            var h = -0x340d631b7bdddcdbL
            for (c in s) {
                h = h xor c.code.toLong()
                h *= 0x100000001b3L
            }
            h = h xor (h ushr 33)
            h *= -0xae502812aa7333L
            h = h xor (h ushr 33)
            h *= -0x3b314601e57a13adL
            h = h xor (h ushr 33)
            return h
        }

        private fun LongArray.contains(v: Long) = java.util.Arrays.binarySearch(this, v) >= 0

        private fun Collection<Long>.toSortedUnique(): LongArray {
            val a = this.toLongArray()
            a.sort()
            var n = 0
            for (i in a.indices) if (i == 0 || a[i] != a[i - 1]) a[n++] = a[i]
            return a.copyOf(n)
        }
    }
}
