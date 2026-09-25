package com.example.eva.data.prefs

import android.content.Context
import android.util.Base64
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class SecureKeyStore(private val context: Context) {

    private val prefs = context.getSharedPreferences("eva_secure_vault", Context.MODE_PRIVATE)
    private val keyAlias = "eva_secret_master_key"
    private val androidKeyStore = "AndroidKeyStore"
    private val transformation = "AES/GCM/NoPadding"

    init {
        initKeyStore()
    }

    private fun initKeyStore() {
        val ks = KeyStore.getInstance(androidKeyStore).apply { load(null) }
        if (!ks.containsAlias(keyAlias)) {
            val keyGenerator = KeyGenerator.getInstance(
                android.security.keystore.KeyProperties.KEY_ALGORITHM_AES,
                androidKeyStore
            )
            val keyGenParameterSpec = android.security.keystore.KeyGenParameterSpec.Builder(
                keyAlias,
                android.security.keystore.KeyProperties.PURPOSE_ENCRYPT or android.security.keystore.KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
            keyGenerator.init(keyGenParameterSpec)
            keyGenerator.generateKey()
        }
    }

    private fun encrypt(plainText: String): String {
        if (plainText.isEmpty()) return ""
        val ks = KeyStore.getInstance(androidKeyStore).apply { load(null) }
        val secretKey = ks.getKey(keyAlias, null) as? SecretKey ?: return plainText
        val cipher = Cipher.getInstance(transformation)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey)
        val iv = cipher.iv
        val encrypted = cipher.doFinal(plainText.toByteArray(StandardCharsets.UTF_8))
        val combined = ByteArray(iv.size + encrypted.size)
        System.arraycopy(iv, 0, combined, 0, iv.size)
        System.arraycopy(encrypted, 0, combined, iv.size, encrypted.size)
        return Base64.encodeToString(combined, Base64.NO_WRAP)
    }

    private fun decrypt(encryptedBase64: String): String {
        if (encryptedBase64.isEmpty()) return ""
        return runCatching {
            val combined = Base64.decode(encryptedBase64, Base64.NO_WRAP)
            val iv = ByteArray(12)
            val cipherText = ByteArray(combined.size - 12)
            System.arraycopy(combined, 0, iv, 0, 12)
            System.arraycopy(combined, 12, cipherText, 0, cipherText.size)
            val ks = KeyStore.getInstance(androidKeyStore).apply { load(null) }
            val secretKey = ks.getKey(keyAlias, null) as? SecretKey ?: return ""
            val cipher = Cipher.getInstance(transformation)
            val spec = GCMParameterSpec(128, iv)
            cipher.init(Cipher.DECRYPT_MODE, secretKey, spec)
            String(cipher.doFinal(cipherText), StandardCharsets.UTF_8)
        }.getOrDefault("")
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

    companion object {
        fun maskKey(key: String): String {
            if (key.isBlank()) return "Not configured"
            if (key.length <= 8) return "••••••••"
            val prefix = key.take(4)
            val suffix = key.takeLast(4)
            return "$prefix••••••••$suffix"
        }
    }
}
