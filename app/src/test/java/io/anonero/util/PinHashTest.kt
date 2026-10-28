package io.anonero.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PinHashTest {

    @Test
    fun `a hashed pin verifies and a wrong one does not`() {
        val stored = PinHash.hash("1234")
        assertTrue(PinHash.matches("1234", stored))
        assertFalse(PinHash.matches("1235", stored))
        assertFalse(PinHash.matches("", stored))
    }

    @Test
    fun `hashes are salted`() {
        assertTrue(PinHash.hash("1234") != PinHash.hash("1234"))
    }

    @Test
    fun `legacy encoded values still verify, and are flagged for replacement`() {
        // exactly what every install upgrading from the old code has in prefs
        val legacy = CrazyPassEncoder.encode("1234".toByteArray() + ByteArray(28))
        assertTrue(PinHash.isLegacy(legacy))
        assertTrue(PinHash.matches("1234", legacy))
        assertFalse(PinHash.matches("9999", legacy))
        assertFalse(PinHash.isLegacy(PinHash.hash("1234")))
    }

    @Test
    fun `empty or mangled stored values never verify`() {
        assertFalse(PinHash.matches("1234", null))
        assertFalse(PinHash.matches("1234", ""))
        assertFalse(PinHash.matches("1234", "v2:garbage"))
        assertFalse(PinHash.matches("1234", "v2:210000:!!!:!!!"))
    }

    @Test
    fun `stored format is versioned`() {
        val stored = PinHash.hash("1234")
        assertEquals("v2", stored.split(":")[0])
        assertEquals("210000", stored.split(":")[1])
    }
}
