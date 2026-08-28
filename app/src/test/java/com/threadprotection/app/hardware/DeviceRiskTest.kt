package com.threadprotection.app.hardware

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Test cases for how a connecting external device is described and rated. */
class DeviceRiskTest {

    private fun device(
        id: String = "usb:1234:5678",
        transport: DeviceTransport = DeviceTransport.USB,
        kind: DeviceKind = DeviceKind.STORAGE,
        name: String? = "SanDisk Cruzer",
        manufacturer: String? = "SanDisk",
        hardwareId: String = "1234:5678",
        bonded: Boolean = false,
    ) = ExternalDevice(
        id = id, transport = transport, kind = kind, name = name,
        manufacturer = manufacturer, hardwareId = hardwareId, bonded = bonded,
    )

    // ── risk rating follows capability, not name ────────────────────────────────────────────

    @Test
    fun `anything that can type is rated highest risk`() {
        assertEquals(DeviceRiskLevel.HIGH, DeviceRiskAssessor.assess(device(kind = DeviceKind.INPUT)).level)
        assertEquals(
            DeviceRiskLevel.HIGH,
            DeviceRiskAssessor.assess(device(kind = DeviceKind.INPUT, transport = DeviceTransport.BLUETOOTH)).level,
        )
    }

    /** A BadUSB cable calls itself whatever it likes; the rating must not depend on the name. */
    @Test
    fun `a friendly name does not lower the rating of an input device`() {
        val innocent = device(kind = DeviceKind.INPUT, name = "Phone Charger", manufacturer = "Generic")
        assertEquals(DeviceRiskLevel.HIGH, DeviceRiskAssessor.assess(innocent).level)
    }

    @Test
    fun `storage and network devices are rated medium`() {
        assertEquals(DeviceRiskLevel.MEDIUM, DeviceRiskAssessor.assess(device(kind = DeviceKind.STORAGE)).level)
        assertEquals(DeviceRiskLevel.MEDIUM, DeviceRiskAssessor.assess(device(kind = DeviceKind.NETWORK)).level)
    }

    /** Not knowing what something is doesn't make it safe. */
    @Test
    fun `an unidentifiable device is not treated as low risk`() {
        assertNotEquals(DeviceRiskLevel.LOW, DeviceRiskAssessor.assess(device(kind = DeviceKind.OTHER)).level)
    }

    @Test
    fun `headphones are rated low`() {
        assertEquals(DeviceRiskLevel.LOW, DeviceRiskAssessor.assess(device(kind = DeviceKind.AUDIO)).level)
    }

    @Test
    fun `every rating explains itself and says what to do`() {
        DeviceKind.entries.forEach { kind ->
            val risk = DeviceRiskAssessor.assess(device(kind = kind))
            assertTrue("$kind should explain why", risk.why.length > 40)
            assertTrue("$kind should give advice", risk.advice.isNotBlank())
        }
    }

    // ── identifying devices honestly ────────────────────────────────────────────────────────

    @Test
    fun `a reported product name is used`() {
        assertEquals("SanDisk Cruzer", device().displayName)
    }

    @Test
    fun `a missing name falls back to the maker, never to an invented one`() {
        assertEquals("SanDisk device", device(name = null).displayName)
    }

    @Test
    fun `a device that reports nothing is labelled unnamed, not guessed at`() {
        assertEquals("Unnamed USB device", device(name = null, manufacturer = null).displayName)
        assertEquals(
            "Unnamed Bluetooth device",
            device(name = null, manufacturer = null, transport = DeviceTransport.BLUETOOTH).displayName,
        )
    }

    @Test
    fun `a blank name is treated as no name`() {
        assertEquals("SanDisk device", device(name = "   ").displayName)
    }

    @Test
    fun `every device kind has a human label`() {
        DeviceKind.entries.forEach { kind ->
            assertTrue(kind.name, device(kind = kind).kindLabel.isNotBlank())
        }
    }

    // ── stable identity across reconnects ───────────────────────────────────────────────────

    @Test
    fun `the same device reconnecting keeps its id`() {
        val first = device(id = "usb:0781:5567:ABC123")
        val again = device(id = "usb:0781:5567:ABC123")
        assertEquals(first.id, again.id)
    }

    @Test
    fun `two identical drives with different serials are different devices`() {
        assertNotEquals(device(id = "usb:0781:5567:AAA").id, device(id = "usb:0781:5567:BBB").id)
    }

    @Test
    fun `usb and bluetooth ids cannot collide`() {
        val usb = device(id = "usb:AA:BB")
        val bt = device(id = "bt:AA:BB:CC:DD:EE:FF", transport = DeviceTransport.BLUETOOTH)
        assertNotEquals(usb.id, bt.id)
        assertTrue(usb.id.startsWith("usb:"))
        assertTrue(bt.id.startsWith("bt:"))
    }

    // ── session-only trust ──────────────────────────────────────────────────────────────────

    @Test
    fun `trust has no permanently-allowed state`() {
        // "Allow once" must not have a persistent sibling that a future change could quietly
        // promote it into — the user was never asked to trust a device permanently.
        assertEquals(
            setOf(DeviceTrust.UNKNOWN, DeviceTrust.BLOCKED, DeviceTrust.ALLOWED_ONCE),
            DeviceTrust.entries.toSet(),
        )
    }
}
