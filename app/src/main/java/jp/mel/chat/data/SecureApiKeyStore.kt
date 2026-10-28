package jp.mel.chat.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

// Adapted from the existing MEL Studio SecureApiKeyStore (AES-GCM / Android Keystore).
class SecureApiKeyStore(context: Context) {
    private val preferences = context.getSharedPreferences("openrouter_credentials", Context.MODE_PRIVATE)

    @Synchronized fun save(value: String) {
        val normalized = value.trim()
        require(normalized.startsWith("sk-or-") && normalized.length > 16 && normalized.none { it.isWhitespace() }) {
            "OpenRouterのAPIキー（sk-or-…）を入力してください。"
        }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val encrypted = cipher.doFinal(normalized.toByteArray(Charsets.UTF_8))
        check(preferences.edit()
            .putString("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .putString("ciphertext", Base64.encodeToString(encrypted, Base64.NO_WRAP)).commit()) {
            "APIキーを保存できませんでした。"
        }
    }

    @Synchronized fun load(): String? {
        val iv = preferences.getString("iv", null) ?: return null
        val encrypted = preferences.getString("ciphertext", null) ?: return null
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)))
            String(cipher.doFinal(Base64.decode(encrypted, Base64.NO_WRAP)), Charsets.UTF_8)
        } catch (_: Exception) {
            throw IllegalStateException("保存したAPIキーを復号できません。設定で再登録してください。")
        }
    }

    @Synchronized fun clear() { check(preferences.edit().clear().commit()) { "APIキーを削除できませんでした。" } }

    private fun getOrCreateKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true).build())
            generateKey()
        }
    }
    private companion object { const val ALIAS = "mel_chat_openrouter_v1" }
}
