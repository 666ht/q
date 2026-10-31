package io.anonero.util.backup

import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.PKCS5S2ParametersGenerator
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.util.encoders.Base64
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

object EncryptUtil {

    const val DEFAULT_ITR = 11000
    const val SALT_PREFIX = "Salted__"
    const val BUFFER_SIZE = 4096

    // v2 backups: PBKDF2-HMAC-SHA256 at the OWASP floor and authenticated encryption.
    // v1 was 11k iterations of the same KDF into unauthenticated AES-CBC -- crackable
    // at hobbyist cost for weak passphrases, and silently restorable after tampering.
    // The magic byte-string is the version: files starting Salted__ are v1 and stay
    // restorable forever, files starting ANONBAK2 are v2. Pure javax.crypto, so this
    // restores unchanged wherever a backup is carried.
    private const val V2_MAGIC = "ANONBAK2"
    private const val V2_ITERATIONS = 600_000
    private const val V2_SALT_BYTES = 16
    private const val V2_IV_BYTES = 12
    private const val V2_TAG_BITS = 128

    private fun FileInputStream.readFully(buffer: ByteArray) {
        var offset = 0
        while (offset < buffer.size) {
            val count = read(buffer, offset, buffer.size - offset)
            if (count < 0) throw java.io.EOFException("Truncated backup file")
            offset += count
        }
    }

    private fun v2Key(passphrase: String, salt: ByteArray): SecretKeySpec {
        val passwordChars = passphrase.toCharArray()
        val keySpec = PBEKeySpec(passwordChars, salt, V2_ITERATIONS, 256)
        return try {
            SecretKeySpec(
                SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                    .generateSecret(keySpec)
                    .encoded,
                "AES",
            )
        } finally {
            keySpec.clearPassword()
            passwordChars.fill('\u0000')
        }
    }


    fun encrypt(
        password: String,
        toEncrypt: String
    ): String {
        val salt = SecureRandom().generateSeed(8)
        // Derive 48 byte key
        var keySpec: SecretKeySpec?
        var ivSpec: IvParameterSpec?
        PKCS5S2ParametersGenerator(SHA256Digest()).let { generator ->
            generator.init(password.toByteArray(), salt, DEFAULT_ITR)
            (generator.generateDerivedMacParameters(48 * 8) as KeyParameter).key.let { secretKey ->
                val bytes = secretKey.copyOfRange(0, 32)
                keySpec = SecretKeySpec(bytes, "AES")
                ivSpec = IvParameterSpec(secretKey.copyOfRange(32, secretKey.size))
                generator.password.fill('*'.code.toByte())
                secretKey.fill('*'.code.toByte())
            }
        }
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, ivSpec)
        val cipherText = cipher.doFinal(toEncrypt.toByteArray())
        return Base64.encode(SALT_PREFIX.toByteArray() + salt + cipherText)
            .decodeToString()
            .replace("(.{64})".toRegex(), "$1\n")

    }

    fun decrypt(
        password: String,
        toDecrypt: String
    ): String {
        val encryptedBytes = Base64.decode(toDecrypt.lines().joinToString(""))
        val salt = encryptedBytes.copyOfRange(8, 16)
        // Derive 48 byte key
        var keySpec: SecretKeySpec?
        var ivSpec: IvParameterSpec?
        PKCS5S2ParametersGenerator(SHA256Digest()).let { generator ->
            generator.init(password.toByteArray(), salt, DEFAULT_ITR)
            (generator.generateDerivedMacParameters(48 * 8) as KeyParameter).key.let { secretKey ->
                // Decryption Key is bytes 0 - 31 of the derived secret key
                val bytes = secretKey.copyOfRange(0, 32)
                keySpec = SecretKeySpec(bytes, "AES")
                // Input Vector is bytes 32 - 47 of the derived secret key
                ivSpec = IvParameterSpec(secretKey.copyOfRange(32, secretKey.size))
                generator.password.fill('*'.code.toByte())
                secretKey.fill('*'.code.toByte())
            }
        }
        val cipherText = encryptedBytes.copyOfRange(16, encryptedBytes.size)
        // Decrypt the Cipher Text and manually remove padding after
        val cipher = Cipher.getInstance("AES/CBC/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, keySpec, ivSpec)
        val decrypted = cipher.doFinal(cipherText)
        // Last byte of the decrypted text is the number of padding bytes needed to remove
        val plaintext = decrypted.copyOfRange(0, decrypted.size - decrypted.last().toInt())
        return plaintext.toString(Charsets.UTF_8)

    }

    /** Writes a v2 backup: ANONBAK2 || salt(16) || iv(12) || GCM ciphertext+tag. */
    fun encryptFile(key: String, inputFile: File, outputFile: File) {
        val salt = ByteArray(V2_SALT_BYTES).also { SecureRandom().nextBytes(it) }
        val iv = ByteArray(V2_IV_BYTES).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, v2Key(key, salt), GCMParameterSpec(V2_TAG_BITS, iv))
        FileInputStream(inputFile).use { input ->
            FileOutputStream(outputFile).use { fileOut ->
                fileOut.write(V2_MAGIC.toByteArray())
                fileOut.write(salt)
                fileOut.write(iv)
                CipherOutputStream(BufferedOutputStream(fileOut), cipher).use { encrypted ->
                    input.copyTo(encrypted, BUFFER_SIZE)
                }
            }
        }
    }

    /**
     * Restores either format. v2 is authenticated: a tampered or truncated file, or a
     * wrong passphrase, fails the GCM tag check and throws instead of yielding garbage
     * -- or worse, plausible garbage. v1 files restore exactly as they always did.
     */
    fun decryptFile(key: String, inputFile: File, outputFile: File) {
        val magic = ByteArray(8)
        FileInputStream(inputFile).use { input ->
            input.readFully(magic)
        }
        if (magic.decodeToString() == V2_MAGIC) return decryptFileV2(key, inputFile, outputFile)
        decryptFileV1(key, inputFile, outputFile)
    }

    private fun decryptFileV2(key: String, inputFile: File, outputFile: File) {
        FileInputStream(inputFile).use { input ->
            val magic = ByteArray(V2_MAGIC.length).also { input.readFully(it) }
            if (magic.decodeToString() != V2_MAGIC) {
                throw java.io.IOException("Invalid authenticated backup header")
            }
            val salt = ByteArray(V2_SALT_BYTES).also { input.readFully(it) }
            val iv = ByteArray(V2_IV_BYTES).also { input.readFully(it) }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, v2Key(key, salt), GCMParameterSpec(V2_TAG_BITS, iv))
            CipherInputStream(BufferedInputStream(input), cipher).use { decrypted ->
                outputFile.outputStream().use { decrypted.copyTo(it, BUFFER_SIZE) }
            }
        }
    }

    private fun decryptFileV1(key: String, inputFile: File, outputFile: File) {
        val prefix = ByteArray(16)
        FileInputStream(inputFile).use { it.readFully(prefix) }
        //Extract salt from the input file, 0-8 is the salt prefix, 8-16 is the salt
        val salt = prefix.copyOfRange(8, 16)
        var keySpec: SecretKeySpec?
        var ivSpec: IvParameterSpec?
        PKCS5S2ParametersGenerator(SHA256Digest()).let { generator ->
            generator.init(key.toByteArray(), salt, DEFAULT_ITR)
            (generator.generateDerivedMacParameters(48 * 8) as KeyParameter).key.let { secretKey ->
                val bytes = secretKey.copyOfRange(0, 32)
                keySpec = SecretKeySpec(bytes, "AES")
                ivSpec = IvParameterSpec(secretKey.copyOfRange(32, secretKey.size))
                generator.password.fill('*'.code.toByte())
                secretKey.fill('*'.code.toByte())
            }
        }
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, keySpec, ivSpec)
        inputFile.inputStream().use { fileInputStream ->
            // Skip the salt prefix and salt without silently accepting a truncated header.
            var remaining = 16L
            while (remaining > 0L) {
                val skipped = fileInputStream.skip(remaining)
                if (skipped == 0L) {
                    if (fileInputStream.read() < 0) throw java.io.EOFException("Truncated V1 backup header")
                    remaining--
                } else {
                    remaining -= skipped
                }
            }
            CipherInputStream(BufferedInputStream(fileInputStream), cipher).use { cis ->
                outputFile.outputStream().use { fos ->
                    cis.copyTo(fos, BUFFER_SIZE)
                }
            }
        }
    }

}