package com.yoyostudios.clashcompanion.api

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Stores the user's Gemini API key encrypted with an AES-GCM key held by Android Keystore.
 * The plaintext key is never written to SharedPreferences or logs.
 */
object ApiKeyStore {
    private const val TAG = "ClashCompanion"
    private const val PREFS = "secure_api_keys"
    private const val KEY_ALIAS = "clash_commander_gemini_key_v1"
    private const val VALUE_KEY = "gemini_ciphertext"
    private const val IV_KEY = "gemini_iv"
    private const val ROBOFLOW_KEY_ALIAS = "clash_commander_roboflow_key_v1"
    private const val ROBOFLOW_VALUE_KEY = "roboflow_ciphertext"
    private const val ROBOFLOW_IV_KEY = "roboflow_iv"

    @Volatile
    private var appContext: Context? = null

    fun initialize(context: Context) {
        appContext = context.applicationContext
    }

    fun isGeminiKeyConfigured(): Boolean = getGeminiKey().isNotBlank()

    fun saveGeminiKey(value: String) {
        val context = appContext ?: throw IllegalStateException("ApiKeyStore not initialized")
        val trimmed = value.trim()
        require(trimmed.isNotEmpty()) { "API key cannot be empty" }

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val encrypted = cipher.doFinal(trimmed.toByteArray(Charsets.UTF_8))
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit()
            .putString(IV_KEY, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .putString(VALUE_KEY, Base64.encodeToString(encrypted, Base64.NO_WRAP))
            .apply()
    }

    fun getGeminiKey(): String {
        val context = appContext ?: return ""
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val ivText = prefs.getString(IV_KEY, null) ?: return ""
        val valueText = prefs.getString(VALUE_KEY, null) ?: return ""

        return try {
            val iv = Base64.decode(ivText, Base64.NO_WRAP)
            val encrypted = Base64.decode(valueText, Base64.NO_WRAP)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, iv))
            String(cipher.doFinal(encrypted), Charsets.UTF_8)
        } catch (e: Exception) {
            Log.e(TAG, "Could not decrypt the saved Gemini API key", e)
            ""
        }
    }

    
    fun isRoboflowKeyConfigured(): Boolean = getRoboflowKey().isNotBlank()

    fun saveRoboflowKey(value: String) {
        val context = appContext ?: throw IllegalStateException("ApiKeyStore not initialized")
        val trimmed = value.trim()
        require(trimmed.isNotEmpty()) { "API key cannot be empty" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey(ROBOFLOW_KEY_ALIAS))
        val encrypted = cipher.doFinal(trimmed.toByteArray(Charsets.UTF_8))
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(ROBOFLOW_IV_KEY, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .putString(ROBOFLOW_VALUE_KEY, Base64.encodeToString(encrypted, Base64.NO_WRAP))
            .apply()
    }

    fun getRoboflowKey(): String {
        val context = appContext ?: return ""
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val ivText = prefs.getString(ROBOFLOW_IV_KEY, null) ?: return ""
        val valueText = prefs.getString(ROBOFLOW_VALUE_KEY, null) ?: return ""
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                getOrCreateKey(ROBOFLOW_KEY_ALIAS),
                GCMParameterSpec(128, Base64.decode(ivText, Base64.NO_WRAP))
            )
            String(cipher.doFinal(Base64.decode(valueText, Base64.NO_WRAP)), Charsets.UTF_8)
        } catch (e: Exception) {
            Log.e(TAG, "Could not decrypt the saved Roboflow API key", e)
            ""
        }
    }

    private fun getOrCreateKey(alias: String): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (keyStore.containsAlias(alias)) return keyStore.getKey(alias, null) as SecretKey
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .setUserAuthenticationRequired(false)
                .build()
        )
        return generator.generateKey()
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (keyStore.containsAlias(KEY_ALIAS)) {
            return keyStore.getKey(KEY_ALIAS, null) as SecretKey
        }

        val generator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            "AndroidKeyStore"
        )
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .setUserAuthenticationRequired(false)
                .build()
        )
        return generator.generateKey()
    }
}
