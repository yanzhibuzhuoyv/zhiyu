package com.zhiyu.app.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.zhiyu.app.domain.Image2Protocol
import com.zhiyu.app.domain.LocalMemoryCodec
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Immutable credential snapshot with safe diagnostics; deliberately not a data class. */
class RemoteImageConfiguration(val key: String, val model: String) {
    override fun equals(other: Any?): Boolean = other is RemoteImageConfiguration && key == other.key && model == other.model
    override fun hashCode(): Int = 31 * key.hashCode() + model.hashCode()
    override fun toString(): String = "RemoteImageConfiguration(model=$model, key=[REDACTED])"
}

/** Independent image-service storage; never changes the chat credential or OC binding. */
class RemoteImageCredentialStore(
    context: Context,
    private val storageName: String = "zhiyu_remote_image2_credentials",
    private val alias: String = "zhiyu_remote_image2_aes_v1",
) {
    companion object { private val lock = Any() }
    init { require(storageName.startsWith("zhiyu_remote_image2_") && alias.startsWith("zhiyu_remote_image2_")) }
    private val prefs = context.applicationContext.getSharedPreferences(storageName, Context.MODE_PRIVATE)
    private fun aad(model: String) = "$alias:$storageName:$model".toByteArray(Charsets.UTF_8)

    /** Model and encrypted key belong to one preference transaction and one lock acquisition. */
    fun configuration(): RemoteImageConfiguration? = synchronized(lock) {
        val value = prefs.getString("encrypted", null) ?: return@synchronized null
        try {
            val model = prefs.getString("model", null) ?: Image2Protocol.DEFAULT_MODEL
            require(model in Image2Protocol.MODELS)
            require(value.length <= 8192)
            val pieces = value.split(':'); require(pieces.size == 2)
            val iv = Base64.decode(pieces[0], Base64.NO_WRAP); require(iv.size == 12)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(false), GCMParameterSpec(128, iv))
            cipher.updateAAD(aad(model))
            val apiKey = LocalMemoryCodec.utf8(cipher.doFinal(Base64.decode(pieces[1], Base64.NO_WRAP))).also(Image2Protocol::checkKey)
            RemoteImageConfiguration(apiKey, model)
        } catch (_: Exception) {
            throw IllegalStateException("本机图像服务密钥无法读取，请重新输入；聊天和恋人绑定已保留")
        }
    }

    fun read(): String? = configuration()?.key

    fun model(): String = configuration()?.model ?: Image2Protocol.DEFAULT_MODEL

    fun write(apiKey: String, model: String = Image2Protocol.DEFAULT_MODEL) = synchronized(lock) {
        Image2Protocol.checkKey(apiKey)
        require(model in Image2Protocol.MODELS) { "图像模型不受支持，请重新选择" }
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key(true)); cipher.updateAAD(aad(model))
            val encrypted = cipher.doFinal(LocalMemoryCodec.bytes(apiKey))
            val encoded = Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(encrypted, Base64.NO_WRAP)
            check(prefs.edit().putString("encrypted", encoded).putString("model", model).commit())
        } catch (_: Exception) { throw IllegalStateException("无法保存本机加密图像服务设置") }
    }

    fun clear() = synchronized(lock) {
        check(prefs.edit().remove("encrypted").remove("model").commit()) { "无法清除本机图像服务密钥" }
    }

    private fun key(create: Boolean): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        check(create) { "图像密钥加密别名不存在" }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build())
            generateKey()
        }
    }
}
