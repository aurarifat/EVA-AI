package com.example.eva.data.prefs

import android.content.Context
import android.util.Base64
import android.util.Log
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class SecureKeyStore(private val context: Context) {

    companion object {
        private const val TAG = "SecureKeyStore"
        private const val KEY_ALIAS = "eva_secret_master_key"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"

        fun maskKey(key: String): String {
            if (key.isBlank()) return "Not configured"
            if (key.length <= 8) return "••••••••"
            val prefix = key.take(4)
            val suffix = key.takeLast(4)
            return "$prefix••••••••$suffix"
        }
    }

    private val prefs = context.getSharedPreferences("eva_secure_vault", Context.MODE_PRIVATE)
    private var isHardwareKeystoreAvailable = false

    init {
        initKeyStoreSafely()
    }

    private fun initKeyStoreSafely() {
        try {
            val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            if (!ks.containsAlias(KEY_ALIAS)) {
                val keyGenerator = KeyGenerator.getInstance(
                    android.security.keystore.KeyProperties.KEY_ALGORITHM_AES,
                    ANDROID_KEYSTORE
                )
                val keyGenParameterSpec = android.security.keystore.KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    android.security.keystore.KeyProperties.PURPOSE_ENCRYPT or android.security.keystore.KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .build()
                keyGenerator.init(keyGenParameterSpec)
                keyGenerator.generateKey()
            }
            isHardwareKeystoreAvailable = true
        } catch (e: Throwable) {
            Log.w(TAG, "AndroidKeyStore initialization unavailable; using fallback vault: ${e.message}")
            isHardwareKeystoreAvailable = false
        }
    }

    private fun encrypt(plainText: String): String {
        if (plainText.isEmpty()) return ""

        if (isHardwareKeystoreAvailable) {
            try {
                val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
                val secretKey = ks.getKey(KEY_ALIAS, null) as? SecretKey
                if (secretKey != null) {
                    val cipher = Cipher.getInstance(TRANSFORMATION)
                    cipher.init(Cipher.ENCRYPT_MODE, secretKey)
                    val iv = cipher.iv
                    val encrypted = cipher.doFinal(plainText.toByteArray(StandardCharsets.UTF_8))
                    val combined = ByteArray(iv.size + encrypted.size)
                    System.arraycopy(iv, 0, combined, 0, iv.size)
                    System.arraycopy(encrypted, 0, combined, iv.size, encrypted.size)
                    return "hw:" + Base64.encodeToString(combined, Base64.NO_WRAP)
                }
            } catch (e: Throwable) {
                Log.w(TAG, "Hardware encryption failed, falling back: ${e.message}")
            }
        }

        // Resilient fallback storage: obfuscated Base64 with package-derived mask
        return "fb:" + obfuscateFallback(plainText)
    }

    private fun decrypt(storedValue: String): String {
        if (storedValue.isEmpty()) return ""

        if (storedValue.startsWith("hw:")) {
            val payload = storedValue.removePrefix("hw:")
            val res = runCatching {
                val combined = Base64.decode(payload, Base64.NO_WRAP)
                val iv = ByteArray(12)
                val cipherText = ByteArray(combined.size - 12)
                System.arraycopy(combined, 0, iv, 0, 12)
                System.arraycopy(combined, 12, cipherText, 0, cipherText.size)
                val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
                val secretKey = ks.getKey(KEY_ALIAS, null) as? SecretKey ?: return@runCatching ""
                val cipher = Cipher.getInstance(TRANSFORMATION)
                val spec = GCMParameterSpec(128, iv)
                cipher.init(Cipher.DECRYPT_MODE, secretKey, spec)
                String(cipher.doFinal(cipherText), StandardCharsets.UTF_8)
            }.getOrDefault("")
            if (res.isNotEmpty()) return res
        }

        if (storedValue.startsWith("fb:")) {
            return deobfuscateFallback(storedValue.removePrefix("fb:"))
        }

        // Backward compatibility: try hardware decrypt, else fallback
        return runCatching {
            val combined = Base64.decode(storedValue, Base64.NO_WRAP)
            if (combined.size > 12) {
                val iv = ByteArray(12)
                val cipherText = ByteArray(combined.size - 12)
                System.arraycopy(combined, 0, iv, 0, 12)
                System.arraycopy(combined, 12, cipherText, 0, cipherText.size)
                val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
                val secretKey = ks.getKey(KEY_ALIAS, null) as? SecretKey ?: return@runCatching ""
                val cipher = Cipher.getInstance(TRANSFORMATION)
                val spec = GCMParameterSpec(128, iv)
                cipher.init(Cipher.DECRYPT_MODE, secretKey, spec)
                String(cipher.doFinal(cipherText), StandardCharsets.UTF_8)
            } else {
                storedValue
            }
        }.getOrElse {
            deobfuscateFallback(storedValue)
        }
    }

    private fun obfuscateFallback(plainText: String): String {
        val mask = (context.packageName + "_eva_vault_salt").toByteArray(StandardCharsets.UTF_8)
        val input = plainText.toByteArray(StandardCharsets.UTF_8)
        val masked = ByteArray(input.size) { i ->
            (input[i].toInt() xor mask[i % mask.size].toInt()).toByte()
        }
        return Base64.encodeToString(masked, Base64.NO_WRAP)
    }

    private fun deobfuscateFallback(encoded: String): String {
        return try {
            val mask = (context.packageName + "_eva_vault_salt").toByteArray(StandardCharsets.UTF_8)
            val decoded = Base64.decode(encoded, Base64.NO_WRAP)
            val unmasked = ByteArray(decoded.size) { i ->
                (decoded[i].toInt() xor mask[i % mask.size].toInt()).toByte()
            }
            String(unmasked, StandardCharsets.UTF_8)
        } catch (_: Exception) {
            encoded
        }
    }

    fun setKey(provider: AiProviderType, key: String) {
        val encrypted = encrypt(key.trim())
        prefs.edit().putString("key_${provider.name}", encrypted).apply()
    }

    fun getKey(provider: AiProviderType): String {
        val enc = prefs.getString("key_${provider.name}", "") ?: ""
        return decrypt(enc)
    }

    fun hasKey(provider: AiProviderType): Boolean {
        return getKey(provider).isNotBlank()
    }

    fun clearKey(provider: AiProviderType) {
        prefs.edit().remove("key_${provider.name}").apply()
    }

    fun setTelegramToken(token: String) {
        val encrypted = encrypt(token.trim())
        prefs.edit().putString("key_telegram_bot_token", encrypted).apply()
    }

    fun getTelegramToken(): String {
        val enc = prefs.getString("key_telegram_bot_token", "") ?: ""
        return decrypt(enc)
    }

    fun hasTelegramToken(): Boolean {
        return getTelegramToken().isNotBlank()
    }

    fun clearTelegramToken() {
        prefs.edit().remove("key_telegram_bot_token").apply()
    }
}
