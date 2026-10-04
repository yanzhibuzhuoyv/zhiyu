package com.zhiyu.app.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.zhiyu.app.domain.AuthSession
import com.zhiyu.app.domain.CredentialStore
import com.zhiyu.app.domain.SavedConnection
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** The preference contains only a random IV and authenticated ciphertext, never raw credentials. */
class SecureCredentialStore(
    context: Context,
    storageName: String = "zhiyu_credentials",
    private val alias: String = "zhiyu_credentials_aes_v1",
) : CredentialStore {
    private val prefs = context.getSharedPreferences(storageName, Context.MODE_PRIVATE)
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build())
            generateKey()
        }
    }
    @Synchronized override fun read(): SavedConnection? {
        val encoded = prefs.getString("encrypted", null) ?: return null
        try {
            val parts = encoded.split(':')
            require(parts.size == 2)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)))
            cipher.updateAAD(alias.toByteArray(Charsets.UTF_8))
            val json = JSONObject(String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), Charsets.UTF_8))
            val session = json.optJSONObject("session")?.let {
                AuthSession(it.getString("token"), it.getString("provider_id"), it.getString("model"), it.getLong("expires_at"))
            }
            return SavedConnection(json.getString("server_url"), json.getString("provider_id"), json.getString("api_key"), json.getString("model"), session)
        } catch (_: Exception) {
            clear()
            throw IllegalStateException("本机加密连接信息无法读取，已清除，请重新输入 API Key")
        }
    }
    @Synchronized override fun write(connection: SavedConnection) {
        val json = JSONObject().put("server_url", connection.serverUrl).put("provider_id", connection.providerId)
            .put("api_key", connection.apiKey).put("model", connection.model)
        connection.session?.let {
            json.put("session", JSONObject().put("token", it.token).put("provider_id", it.providerId)
                .put("model", it.model).put("expires_at", it.expiresAt))
        }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        cipher.updateAAD(alias.toByteArray(Charsets.UTF_8))
        val ciphertext = cipher.doFinal(json.toString().toByteArray(Charsets.UTF_8))
        val encoded = Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(ciphertext, Base64.NO_WRAP)
        check(prefs.edit().putString("encrypted", encoded).commit()) { "无法保存本机加密连接信息" }
    }
    @Synchronized override fun clear() {
        check(prefs.edit().clear().commit()) { "无法清除本机连接信息" }
    }
}
