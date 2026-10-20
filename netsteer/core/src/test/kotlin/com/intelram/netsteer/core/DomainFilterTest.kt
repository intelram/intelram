package com.intelram.netsteer.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import kotlin.random.Random

class DomainFilterTest {
    private fun filter(vararg lines: String) = DomainFilter.Builder().apply { addList(lines.joinToString("\n")) }.build()

    @Test fun adblockRuleBlocksDomainAndSubdomainsOnly() {
        val f = filter("||doubleclick.net^")
        assertTrue(f.blocks("doubleclick.net"))
        assertTrue(f.blocks("ad.g.doubleclick.net"))
        assertTrue(f.blocks("AD.DoubleClick.NET.")) // case and trailing dot
        assertFalse(f.blocks("notdoubleclick.net"))
        assertFalse(f.blocks("doubleclick.network"))
        assertFalse(f.blocks("net"))
    }

    @Test fun exceptionsWinUnlessImportant() {
        val f = filter("||ads.example.com^", "@@||good.ads.example.com^", "||tracker.example.org^\$important", "@@||tracker.example.org^")
        assertTrue(f.blocks("x.ads.example.com"))
        assertFalse(f.blocks("good.ads.example.com"))
        assertFalse(f.blocks("a.good.ads.example.com"))
        assertTrue(f.blocks("tracker.example.org"))
    }

    @Test fun badfilterCancelsRuleFromAnotherList() {
        val b = DomainFilter.Builder()
        b.addList("||cdn.example.com^")
        b.addList("||cdn.example.com^\$badfilter")
        assertFalse(b.build().blocks("cdn.example.com"))
    }

    @Test fun hostsAndPlainFormats() {
        val f = filter(
            "# comment", "0.0.0.0 ads.one.com", "127.0.0.1\ttrack.two.com # inline comment",
            "0.0.0.0 0.0.0.0", "127.0.0.1 localhost", "plain.three.com", ".bbelements.com^",
        )
        assertTrue(f.blocks("ads.one.com"))
        assertTrue(f.blocks("track.two.com"))
        assertTrue(f.blocks("plain.three.com"))
        assertTrue(f.blocks("x.bbelements.com"))
        assertFalse(f.blocks("localhost"))
        assertEquals(4, f.size)
    }

    @Test fun unsupportedRulesAreSkippedNotMisapplied() {
        val b = DomainFilter.Builder()
        val accepted = b.addList(
            listOf(
                "example.com##.banner", "example.com#@#.ad", "||ads*.example.com^", "/^ad[0-9]\\.example\\.com$/",
                "||example.com/ads/banner.js", "||example.com^\$domain=foo.com", "||com^", "! comment", "[Adblock Plus 2.0]",
                "||ok.example.net^\$third-party",
            ).joinToString("\n"),
        )
        val f = b.build()
        assertEquals(1, accepted)
        assertTrue(f.blocks("ok.example.net"))
        assertFalse(f.blocks("example.com"))
        assertFalse(f.blocks("anything.com"))
    }

    @Test fun userAllowlistOverridesLists() {
        val f = filter("||ads.example.com^").withAllowed(listOf("ads.example.com", "not a domain"))
        assertFalse(f.blocks("ads.example.com"))
        assertFalse(f.blocks("x.ads.example.com"))
    }

    @Test fun cacheRoundTripAndCorruptionDetection() {
        val f = filter("||a.com^", "||b.org^\$important", "@@||ok.a.com^")
        val bytes = ByteArrayOutputStream().also { f.writeTo(DataOutputStream(it)) }.toByteArray()
        val g = DomainFilter.readFrom(DataInputStream(ByteArrayInputStream(bytes)))
        for (h in listOf("a.com", "x.a.com", "ok.a.com", "b.org", "c.net")) assertEquals(h, f.blocks(h), g.blocks(h))
        bytes[0] = 0
        try {
            DomainFilter.readFrom(DataInputStream(ByteArrayInputStream(bytes)))
            throw AssertionError("expected corruption error")
        } catch (_: IOException) {
        }
    }

    @Test fun noFalsePositivesAtListScale() {
        val rnd = Random(7)
        fun name() = (1..12).map { 'a' + rnd.nextInt(26) }.joinToString("") + ".com"
        val b = DomainFilter.Builder()
        val listed = (1..200_000).map { name() }
        listed.forEach { b.addBlocked(it) }
        val f = b.build()
        assertTrue(listed.take(1000).all { f.blocks(it) })
        val listedSet = listed.toHashSet()
        val others = (1..200_000).map { name() }.filter { it !in listedSet }
        assertEquals(0, others.count { f.blocks(it) })
    }

    @Test fun suffixesExcludeBareTld() {
        assertEquals(
            listOf("a.b.example.co.uk", "b.example.co.uk", "example.co.uk", "co.uk").map(DomainFilter::hash),
            DomainFilter.suffixHashes("a.b.example.co.uk"),
        )
    }
}
