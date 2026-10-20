package com.intelram.netsteer.core

/**
 * Reads the destination host name from the first bytes a client sends, without
 * decrypting anything: the TLS ClientHello's server_name (SNI) for HTTPS, or
 * the Host header for plain HTTP. Used to block ad domains for connections
 * that were made to an IP address (e.g. when an app uses its own DNS).
 */
object Sniffer {
    const val MAX_BYTES = 16 * 1024

    /** How many bytes are needed to hold the whole first TLS record; null if this isn't a TLS handshake. */
    fun tlsRecordTotal(b: ByteArray, len: Int): Int? {
        if (len < 1 || b[0].toInt() != 0x16) return null
        if (len < 5) return 5
        val recLen = u16(b, 3)
        return minOf(5 + recLen, MAX_BYTES)
    }

    /** SNI host from a ClientHello in [b], or null if absent/incomplete/not TLS. */
    fun tlsSni(b: ByteArray, len: Int): String? {
        try {
            if (len < 5 || b[0].toInt() != 0x16) return null
            val end = minOf(len, 5 + u16(b, 3))
            var p = 5
            if (p + 4 > end || b[p].toInt() != 0x01) return null // ClientHello
            p += 4 // type + 24-bit length
            p += 2 + 32 // legacy_version + random
            if (p >= end) return null
            p += 1 + (b[p].toInt() and 0xFF) // session id
            if (p + 2 > end) return null
            p += 2 + u16(b, p) // cipher suites
            if (p >= end) return null
            p += 1 + (b[p].toInt() and 0xFF) // compression methods
            if (p + 2 > end) return null
            val extEnd = minOf(end, p + 2 + u16(b, p))
            p += 2
            while (p + 4 <= extEnd) {
                val type = u16(b, p)
                val l = u16(b, p + 2)
                p += 4
                if (type == 0x0000 && p + 2 <= extEnd) {
                    var q = p + 2
                    val listEnd = minOf(extEnd, p + 2 + u16(b, p))
                    while (q + 3 <= listEnd) {
                        val nameType = b[q].toInt() and 0xFF
                        val nl = u16(b, q + 1)
                        q += 3
                        if (q + nl > listEnd) return null
                        if (nameType == 0) return ascii(b, q, nl)
                        q += nl
                    }
                    return null
                }
                p += l
            }
        } catch (_: IndexOutOfBoundsException) {
        }
        return null
    }

    /** True once [b] holds a complete HTTP request header block (or cannot be HTTP). */
    fun httpHeaderComplete(b: ByteArray, len: Int): Boolean {
        if (len > 0 && !(b[0] in 'A'.code.toByte()..'Z'.code.toByte())) return true // not an HTTP method
        for (i in 3 until len) {
            if (b[i - 3].toInt() == '\r'.code && b[i - 2].toInt() == '\n'.code && b[i - 1].toInt() == '\r'.code && b[i].toInt() == '\n'.code) return true
        }
        return len >= MAX_BYTES
    }

    /** Host header value (without port) from an HTTP/1.x request, or null. */
    fun httpHost(b: ByteArray, len: Int): String? {
        val text = String(b, 0, len, Charsets.ISO_8859_1)
        val firstLine = text.substringBefore("\r\n")
        if (!firstLine.contains(" HTTP/1.")) return null
        for (line in text.split("\r\n").drop(1)) {
            if (line.isEmpty()) break
            val colon = line.indexOf(':')
            if (colon > 0 && line.substring(0, colon).trim().equals("host", ignoreCase = true)) {
                val v = line.substring(colon + 1).trim()
                return (if (v.startsWith("[")) v.substringBefore(']').removePrefix("[") else v.substringBefore(':')).ifEmpty { null }
            }
        }
        return null
    }

    private fun u16(b: ByteArray, i: Int) = ((b[i].toInt() and 0xFF) shl 8) or (b[i + 1].toInt() and 0xFF)

    private fun ascii(b: ByteArray, off: Int, n: Int): String? {
        for (i in off until off + n) {
            val c = b[i].toInt() and 0xFF
            if (c < 0x21 || c > 0x7E) return null
        }
        return String(b, off, n, Charsets.US_ASCII)
    }
}

/** Minimal DNS helpers for blocking queries that apps send to their own resolvers. */
object Dns {
    /** First question's name of a standard query, or null. */
    fun queryName(m: ByteArray, len: Int): String? {
        if (len < 12) return null
        val flags = ((m[2].toInt() and 0xFF) shl 8) or (m[3].toInt() and 0xFF)
        if (flags and 0x8000 != 0) return null // a response, not a query
        if (flags and 0x7800 != 0) return null // opcode != QUERY
        val qd = ((m[4].toInt() and 0xFF) shl 8) or (m[5].toInt() and 0xFF)
        if (qd < 1) return null
        val sb = StringBuilder()
        var p = 12
        while (true) {
            if (p >= len) return null
            val l = m[p].toInt() and 0xFF
            if (l == 0) break
            if (l and 0xC0 != 0) return null // compression isn't used in questions
            if (p + 1 + l > len) return null
            if (sb.isNotEmpty()) sb.append('.')
            for (i in p + 1..p + l) sb.append((m[i].toInt() and 0xFF).toChar())
            p += 1 + l
            if (sb.length > 253) return null
        }
        return sb.toString().ifEmpty { null }
    }

    /** NXDOMAIN answer to [query] (header + first question echoed). */
    fun nxdomain(query: ByteArray, len: Int): ByteArray? {
        if (queryName(query, len) == null) return null
        var p = 12
        while (query[p].toInt() != 0) p += 1 + (query[p].toInt() and 0xFF)
        val qEnd = p + 1 + 4 // root label + QTYPE + QCLASS
        if (qEnd > len) return null
        val r = query.copyOf(qEnd)
        r[2] = ((query[2].toInt() and 0x01) or 0x80).toByte() // QR=1, keep RD
        r[3] = (0x80 or 0x03).toByte() // RA=1, RCODE=NXDOMAIN
        r[4] = 0; r[5] = 1 // QDCOUNT=1
        for (i in 6..11) r[i] = 0 // AN/NS/AR = 0
        return r
    }
}
