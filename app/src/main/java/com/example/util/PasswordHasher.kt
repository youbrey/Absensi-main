package com.example.util

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/** Salted, deliberately slow local credential hashes; never a shared default password. */
object PasswordHasher {
    private const val ITERATIONS = 120000
    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
    private fun bytes(hex: String) = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private fun derive(password: String, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(password.toCharArray(), salt, ITERATIONS, 256)
        return try { SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1").generateSecret(spec).encoded }
        finally { spec.clearPassword() }
    }
    fun hash(password: String): String {
        require(password.length >= 8) { "Kata sandi minimal 8 karakter" }
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        return "pbkdf2:${hex(salt)}:${hex(derive(password, salt))}"
    }
    fun verify(password: String, stored: String): Boolean = try {
        val parts = stored.split(':')
        parts.size == 3 && parts[0] == "pbkdf2" &&
            MessageDigest.isEqual(derive(password, bytes(parts[1])), bytes(parts[2]))
    } catch (_: Exception) { false }
}
