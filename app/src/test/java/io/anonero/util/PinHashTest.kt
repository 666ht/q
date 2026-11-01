package io.anonero.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PinHashTest {
    @Test
    fun saltedHashMatchesOnlyTheOriginalPin() {
        val first = PinHash.hash("482619")
        val second = PinHash.hash("482619")

        assertNotEquals(first, second)
        assertTrue(PinHash.matches("482619", first))
        assertFalse(PinHash.matches("482610", first))
        assertFalse(PinHash.isLegacy(first))
    }

    @Test
    fun legacyReversiblePinValuesRemainVerifiableForMigration() {
        val pin = "482619"
        val bytes = pin.toByteArray().let { value ->
            if (value.size < 32) value + ByteArray(32 - value.size) else value
        }
        val legacy = CrazyPassEncoder.encode(bytes)

        assertTrue(PinHash.isLegacy(legacy))
        assertTrue(PinHash.matches(pin, legacy))
        assertFalse(PinHash.matches("482610", legacy))
    }

    @Test
    fun migrationRollbackRestoresWalletAndKeysFilesTogether() {
        val directory = java.nio.file.Files.createTempDirectory("wallet-key-migration").toFile()
        try {
            val walletPath = java.io.File(directory, "anon")
            val keysFile = java.io.File("${walletPath.path}.keys")
            val walletSnapshot = java.io.File("${walletPath.path}.premigrate")
            val keysSnapshot = java.io.File("${keysFile.path}.premigrate")

            walletPath.writeText("new-password-wallet")
            keysFile.writeText("new-password-keys")
            walletSnapshot.writeText("pin-password-wallet")
            keysSnapshot.writeText("pin-password-keys")

            assertTrue(WalletKey.restoreMigrationSnapshots(walletPath.path))
            assertEquals("pin-password-wallet", walletPath.readText())
            assertEquals("pin-password-keys", keysFile.readText())
            assertFalse(walletSnapshot.exists())
            assertFalse(keysSnapshot.exists())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun missingMigrationSnapshotsDoNotClaimRollback() {
        val directory = java.nio.file.Files.createTempDirectory("wallet-key-no-snapshot").toFile()
        try {
            val walletPath = java.io.File(directory, "anon")
            assertFalse(WalletKey.restoreMigrationSnapshots(walletPath.path))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun malformedVersionedHashesAreRejected() {
        assertFalse(PinHash.matches("482619", "v2:not-a-number:bad:bad"))
        assertFalse(PinHash.matches("482619", "v2:210000:AA==:AA=="))
    }
}
