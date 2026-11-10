package ua.madeisy.atlantic.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Зберігає email і пароль Cozytouch, зашифровані ключем з Android Keystore
 * (ключ не покидає пристрій). Пароль потрібен, бо хмара видає токен лише на годину.
 */
class CredentialStore(context: Context) {
    private val prefs = context.getSharedPreferences("cozy_credentials", Context.MODE_PRIVATE)

    fun save(email: String, password: String) {
        prefs.edit()
            .putString("email", email)
            .putString("password", encrypt(password))
            .apply()
    }

    fun email(): String? = prefs.getString("email", null)

    fun load(): Pair<String, String>? {
        val e = prefs.getString("email", null) ?: return null
        val p = prefs.getString("password", null) ?: return null
        return runCatching { e to decrypt(p) }.getOrNull()
    }

    fun clearPassword() {
        prefs.edit().remove("password").apply()
    }

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    private fun encrypt(plain: String): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key())
        val out = c.iv + c.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(out, Base64.NO_WRAP)
    }

    private fun decrypt(enc: String): String {
        val all = Base64.decode(enc, Base64.NO_WRAP)
        val iv = all.copyOfRange(0, 12)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
        return String(c.doFinal(all.copyOfRange(12, all.size)), Charsets.UTF_8)
    }

    private companion object {
        const val ALIAS = "cozytouch_credentials"
    }
}
