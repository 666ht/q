package io.anonero.util.backup

import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.PKCS5S2ParametersGenerator
import org.bouncycastle.crypto.params.KeyParameter
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class EncryptUtilTest {
    @Test
    fun newBackupUsesAuthenticatedV2AndRoundTrips() {
        withTempDirectory { directory ->
            val original = "wallet backup fixture".toByteArray()
            val source = File(directory, "source.bin").apply { writeBytes(original) }
            val encrypted = File(directory, "backup.anon")
            val restored = File(directory, "restored.bin")

            EncryptUtil.encryptFile("correct horse battery staple", source, encrypted)

            val header = encrypted.readBytes().copyOfRange(0, 8)
            assertEquals("ANONBAK2", String(header, StandardCharsets.US_ASCII))

            EncryptUtil.decryptFile("correct horse battery staple", encrypted, restored)
            assertArrayEquals(original, restored.readBytes())
        }
    }

    @Test
    fun tamperedV2BackupFailsAuthentication() {
        withTempDirectory { directory ->
            val source = File(directory, "source.bin").apply {
                writeBytes(ByteArray(1024) { index -> (index % 251).toByte() })
            }
            val encrypted = File(directory, "backup.anon")
            val restored = File(directory, "restored.bin")

            EncryptUtil.encryptFile("correct horse battery staple", source, encrypted)
            val bytes = encrypted.readBytes()
            bytes[bytes.lastIndex] = (bytes.last().toInt() xor 0x01).toByte()
            encrypted.writeBytes(bytes)

            var rejected = false
            try {
                EncryptUtil.decryptFile("correct horse battery staple", encrypted, restored)
            } catch (_: Exception) {
                rejected = true
            }
            assertTrue("Modified backup must fail its GCM authentication tag", rejected)
        }
    }

    @Test
    fun legacyV1BackupRemainsRestorable() {
        withTempDirectory { directory ->
            val password = "old backup password"
            val original = "legacy wallet backup".toByteArray()
            val encrypted = File(directory, "legacy.anon")
            val restored = File(directory, "restored.bin")

            writeV1Backup(password, original, encrypted)
            EncryptUtil.decryptFile(password, encrypted, restored)

            assertArrayEquals(original, restored.readBytes())
        }
    }

    private fun writeV1Backup(password: String, plaintext: ByteArray, destination: File) {
        val salt = ByteArray(8) { index -> (index + 1).toByte() }
        val generator = PKCS5S2ParametersGenerator(SHA256Digest())
        generator.init(password.toByteArray(), salt, EncryptUtil.DEFAULT_ITR)
        val derived = (generator.generateDerivedMacParameters(48 * 8) as KeyParameter).key
        val key = SecretKeySpec(derived.copyOfRange(0, 32), "AES")
        val iv = IvParameterSpec(derived.copyOfRange(32, 48))
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, key, iv)
        val ciphertext = cipher.doFinal(plaintext)
        destination.writeBytes("Salted__".toByteArray() + salt + ciphertext)
        derived.fill(0)
    }

    private inline fun withTempDirectory(block: (File) -> Unit) {
        val directory = Files.createTempDirectory("anonero-backup-test").toFile()
        try {
            block(directory)
        } finally {
            directory.deleteRecursively()
        }
    }
}
