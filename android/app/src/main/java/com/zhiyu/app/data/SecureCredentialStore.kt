package com.zhiyu.app.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.zhiyu.app.domain.AuthSession
import com.zhiyu.app.domain.CompanionBinding
import com.zhiyu.app.domain.CredentialStore
import com.zhiyu.app.domain.SavedConnection
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Credentials and the durable AI binding are authenticated ciphertext with separate AAD. */
class SecureCredentialStore(
    context: Context,
    storageName: String = "zhiyu_credentials",
    private val alias: String = "zhiyu_credentials_aes_v1",
) : CredentialStore {
    companion object { private val storageLock = Any() }
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
    private fun decode(encoded: String, purpose: String): JSONObject {
        val parts = encoded.split(':')
        require(parts.size == 2)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)))
        cipher.updateAAD(purpose.toByteArray(Charsets.UTF_8))
        return JSONObject(String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), Charsets.UTF_8))
    }
    private fun encode(json: JSONObject, purpose: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        cipher.updateAAD(purpose.toByteArray(Charsets.UTF_8))
        val ciphertext = cipher.doFinal(json.toString().toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(ciphertext, Base64.NO_WRAP)
    }
    override fun read(): SavedConnection? = synchronized(storageLock) {
        val encoded = prefs.getString("encrypted", null) ?: return@synchronized null
        try {
            val json = decode(encoded, alias)
            val session = json.optJSONObject("session")?.let {
                AuthSession(it.getString("token"), it.getString("provider_id"), it.getString("model"), it.getLong("expires_at"))
            }
            SavedConnection(json.getString("server_url"), json.getString("provider_id"), json.getString("api_key"), json.getString("model"), session)
        } catch (_: Exception) {
            clear()
            throw IllegalStateException("本机加密连接信息无法读取，已清除，请重新输入 API Key")
        }
    }
    override fun write(connection: SavedConnection) = synchronized(storageLock) {
        val json = JSONObject().put("server_url", connection.serverUrl).put("provider_id", connection.providerId)
            .put("api_key", connection.apiKey).put("model", connection.model)
        connection.session?.let {
            json.put("session", JSONObject().put("token", it.token).put("provider_id", it.providerId)
                .put("model", it.model).put("expires_at", it.expiresAt))
        }
        check(prefs.edit().putString("encrypted", encode(json, alias)).commit()) { "无法保存本机加密连接信息" }
    }
    override fun readBinding(): CompanionBinding? = synchronized(storageLock) {
        val encoded = prefs.getString("companion_binding", null) ?: return@synchronized null
        try {
            val json = decode(encoded, "$alias:companion")
            CompanionBinding(json.getString("provider_id"), json.getString("model"), json.optJSONObject("profile")?.profile(), json.optBoolean("profile_configured", false)).also {
                require(it.providerId.isNotBlank() && it.model.isNotBlank())
            }
        } catch (_: Exception) {
            throw IllegalStateException("本机 AI 绑定信息无法验证，不能重新选择 AI")
        }
    }
    override fun writeBinding(binding: CompanionBinding) = synchronized(storageLock) {
        require(binding.providerId.isNotBlank() && binding.model.isNotBlank())
        val existing = readBinding()
        val sameAi = existing?.providerId == binding.providerId && existing.model == binding.model
        val legacyUpgrade = existing?.profileConfigured == false && binding.profileConfigured
        val userTraining = existing?.profileConfigured == true && binding.profileConfigured &&
            existing.profile?.mode != "preset" && binding.profile?.mode == "custom"
        check(existing == null || existing == binding || (sameAi && (legacyUpgrade || userTraining))) { "已绑定的 AI 不能更换" }
        val json = JSONObject().put("provider_id", binding.providerId).put("model", binding.model)
            .put("profile", binding.profile?.json() ?: JSONObject.NULL).put("profile_configured", binding.profileConfigured)
        check(prefs.edit().putString("companion_binding", encode(json, "$alias:companion")).commit()) { "无法保存 AI 绑定信息" }
    }
    override fun clear() = synchronized(storageLock) {
        check(prefs.edit().remove("encrypted").commit()) { "无法清除本机连接信息" }
    }
    override fun compareAndWrite(expected: SavedConnection?, updated: SavedConnection): Boolean = synchronized(storageLock) {
        if (read() != expected) false else { write(updated); true }
    }
}
