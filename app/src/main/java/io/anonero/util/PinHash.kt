package io.anonero.util

import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Versioned PIN verifier. Existing CrazyPassEncoder values remain verifiable and
 * are replaced with a salted PBKDF2 hash after the next successful PIN check.
 */
object PinHash {
    private const val PREFIX = "v2"
    private const val ITERATIONS = 210_000
    private const val SALT_BYTES = 16
    private const val KEY_BITS = 256
    private const val MAX_STORED_ITERATIONS = 2_000_000

    fun hash(pin: String): String {
        val salt = ByteArray(SALT_BYTES).also { SecureRandom().nextBytes(it) }
        val derived = derive(pin, salt, ITERATIONS)
        return listOf(
            PREFIX,
            ITERATIONS.toString(),
            Base64.getEncoder().encodeToString(salt),
            Base64.getEncoder().encodeToString(derived),
        ).joinToString(":")
    }

    fun matches(pin: String, stored: String?): Boolean {
        if (stored.isNullOrEmpty()) return false
        if (isLegacy(stored)) {
            return constantTimeEquals(
                legacyEncode(pin).toByteArray(Charsets.UTF_8),
                stored.toByteArray(Charsets.UTF_8),
            )
        }

        val parts = stored.split(":")
        if (parts.size != 4 || parts[0] != PREFIX) return false
        val iterations = parts[1].toIntOrNull() ?: return false
        if (iterations !in 1..MAX_STORED_ITERATIONS) return false
        val salt = runCatching { Base64.getDecoder().decode(parts[2]) }.getOrNull() ?: return false
        val expected = runCatching { Base64.getDecoder().decode(parts[3]) }.getOrNull() ?: return false
        if (salt.size != SALT_BYTES || expected.size != KEY_BITS / 8) return false
        return constantTimeEquals(derive(pin, salt, iterations), expected)
    }

    fun isLegacy(stored: String?): Boolean =
        !stored.isNullOrEmpty() && !stored.startsWith("$PREFIX:")

    private fun legacyEncode(pin: String): String = CrazyPassEncoder.encode(
        pin.toByteArray(Charsets.UTF_8).let { bytes ->
            if (bytes.size < 32) bytes + ByteArray(32 - bytes.size) else bytes
        }
    )

    private fun derive(pin: String, salt: ByteArray, iterations: Int): ByteArray {
        val passwordChars = pin.toCharArray()
        val spec = PBEKeySpec(passwordChars, salt, iterations, KEY_BITS)
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                .generateSecret(spec)
                .encoded
        } finally {
            spec.clearPassword()
            passwordChars.fill('\u0000')
        }
    }

    private fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean {
        if (a.size != b.size) return false
        var difference = 0
        for (index in a.indices) {
            difference = difference or (a[index].toInt() xor b[index].toInt())
        }
        return difference == 0
    }
}
