package com.threadprotection.app.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Test cases for handling multiple nearby devices and intermittent discovery — the behaviour behind
 * "show all discoverable phones, with their real name, and keep the list stable".
 */
class NearbyDeviceListTest {

    private fun device(
        address: String,
        name: String = "Pixel 8",
        rssi: Int? = -50,
        lastSeenMs: Long = 1_000L,
    ) = BtDeviceInfo(address = address, name = name, bonded = false, rssi = rssi, kind = BtDeviceKind.PHONE, lastSeenMs = lastSeenMs)

    @Test
    fun `a new address is appended`() {
        val list = NearbyDeviceList.merge(emptyList(), device("AA"))
        assertEquals(1, list.size)
        assertEquals("AA", list[0].address)
    }

    @Test
    fun `several distinct phones all appear`() {
        var list = emptyList<BtDeviceInfo>()
        list = NearbyDeviceList.merge(list, device("AA", "Pixel 8"))
        list = NearbyDeviceList.merge(list, device("BB", "Galaxy S24"))
        list = NearbyDeviceList.merge(list, device("CC", "OnePlus 12"))
        assertEquals(3, list.size)
        assertEquals(setOf("Pixel 8", "Galaxy S24", "OnePlus 12"), list.map { it.name }.toSet())
    }

    @Test
    fun `re-sighting the same address updates in place rather than duplicating`() {
        var list = NearbyDeviceList.merge(emptyList(), device("AA", rssi = -80, lastSeenMs = 1_000L))
        list = NearbyDeviceList.merge(list, device("AA", rssi = -40, lastSeenMs = 2_000L))
        assertEquals(1, list.size)
        assertEquals(-40, list[0].rssi)
        assertEquals(2_000L, list[0].lastSeenMs)
    }

    /** A name arriving in a later scan-response packet must be adopted. */
    @Test
    fun `a real name replaces the placeholder`() {
        var list = NearbyDeviceList.merge(emptyList(), device("AA", NearbyDeviceList.PLACEHOLDER_NAME))
        list = NearbyDeviceList.merge(list, device("AA", "Sam's Galaxy S24"))
        assertEquals("Sam's Galaxy S24", list[0].name)
    }

    /** Intermittent discovery: a cycle where the name doesn't arrive must not blank it out. */
    @Test
    fun `a known name is not downgraded back to the placeholder`() {
        var list = NearbyDeviceList.merge(emptyList(), device("AA", "Sam's Galaxy S24"))
        list = NearbyDeviceList.merge(list, device("AA", NearbyDeviceList.PLACEHOLDER_NAME, rssi = -70))
        assertEquals("Sam's Galaxy S24", list[0].name)
        assertEquals("signal still refreshes", -70, list[0].rssi)
    }

    @Test
    fun `devices that stopped advertising are dropped, the rest stay`() {
        val list = listOf(
            device("AA", lastSeenMs = 10_000L),
            device("BB", lastSeenMs = 2_000L),
            device("CC", lastSeenMs = 9_500L),
        )
        val fresh = NearbyDeviceList.dropStale(list, cutoffMs = 5_000L)
        assertEquals(listOf("AA", "CC"), fresh.map { it.address })
    }

    @Test
    fun `a device exactly on the cutoff is kept`() {
        val fresh = NearbyDeviceList.dropStale(listOf(device("AA", lastSeenMs = 5_000L)), cutoffMs = 5_000L)
        assertEquals(1, fresh.size)
    }

    @Test
    fun `display order puts the strongest signal first`() {
        val ordered = NearbyDeviceList.forDisplay(
            listOf(device("AA", "Far", rssi = -90), device("BB", "Near", rssi = -35), device("CC", "Mid", rssi = -60)),
        )
        assertEquals(listOf("Near", "Mid", "Far"), ordered.map { it.name })
    }

    @Test
    fun `a device with no signal reading sorts last instead of being dropped`() {
        val ordered = NearbyDeviceList.forDisplay(listOf(device("AA", "Unknown", rssi = null), device("BB", "Known", rssi = -70)))
        assertEquals(listOf("Known", "Unknown"), ordered.map { it.name })
        assertEquals("nothing is hidden", 2, ordered.size)
    }

    @Test
    fun `display order is stable for equal signal strength`() {
        val input = listOf(device("AA", "Bravo", rssi = -50), device("BB", "Alpha", rssi = -50))
        assertEquals(NearbyDeviceList.forDisplay(input).map { it.name }, NearbyDeviceList.forDisplay(input.reversed()).map { it.name })
    }

    /** A device that goes out of range and comes back must reappear, not stay filtered out. */
    @Test
    fun `a device that returns after going stale is listed again`() {
        var list = listOf(device("AA", lastSeenMs = 1_000L))
        list = NearbyDeviceList.dropStale(list, cutoffMs = 5_000L)
        assertTrue(list.isEmpty())
        list = NearbyDeviceList.merge(list, device("AA", lastSeenMs = 9_000L))
        assertEquals(1, list.size)
    }
}
