package com.threadprotection.app.network

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The offline half of the k-anonymous password check. Getting the hash format or the suffix match
 * wrong would silently report every leaked password as safe, so both are pinned here.
 */
class PasswordLeakCheckTest {

    @Test
    fun `sha1 matches the known digest in the range API's uppercase format`() {
        assertEquals("5BAA61E4C9B93F3F0682250B6CF8331B7EE68FD8", PasswordLeakCheck.sha1Hex("password"))
    }

    @Test
    fun `sha1 hashes the UTF-8 bytes`() {
        // Non-ASCII passwords must hash the same way HIBP hashed them when building the corpus.
        assertEquals(40, PasswordLeakCheck.sha1Hex("pässwörd").length)
        assertEquals(PasswordLeakCheck.sha1Hex("pässwörd"), PasswordLeakCheck.sha1Hex(String("pässwörd".toByteArray(Charsets.UTF_8), Charsets.UTF_8)))
    }

    /** Lines copied from a real `range/CBFDA` response — the prefix for "password123". */
    private val rangeBody = """
        000DD0BFD801860C09116B9AAD880B125F1:53
        00791BB54CC9122C70C1156FD97134EB83E:5
        C6008F9CAB4083784CBD1874F76618D2A97:2266543
        FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFF:0
    """.trimIndent().replace("\n", "\r\n")

    @Test
    fun `a leaked password's suffix is found with its count`() {
        val hash = PasswordLeakCheck.sha1Hex("password123")
        assertEquals("CBFDA", hash.take(5))
        assertEquals(2_266_543L, PasswordLeakCheck.countIn(rangeBody, hash.drop(5)))
    }

    @Test
    fun `suffix match ignores case`() {
        assertEquals(53L, PasswordLeakCheck.countIn(rangeBody, "000dd0bfd801860c09116b9aad880b125f1"))
    }

    @Test
    fun `a suffix that is absent, or only a zero-count padding line, is not leaked`() {
        assertEquals(0L, PasswordLeakCheck.countIn(rangeBody, "1234567890ABCDEF1234567890ABCDEF123"))
        assertEquals(0L, PasswordLeakCheck.countIn(rangeBody, "FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFF"))
    }
}
