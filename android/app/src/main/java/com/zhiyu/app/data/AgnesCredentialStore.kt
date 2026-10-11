package com.zhiyu.app.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.zhiyu.app.domain.AgnesImageProtocol
import com.zhiyu.app.domain.LocalMemoryCodec
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Separate image-service Key and AES alias; never reads or alters chat credentials or binding. */
class AgnesCredentialStore(
    context: Context,
    storageName: String = "zhiyu_agnes_image_credentials",
    private val alias: String = "zhiyu_agnes_image_aes_v1",
) {
    companion object { private val lock = Any() }
    init { require(storageName.startsWith("zhiyu_agnes_") && alias.startsWith("zhiyu_agnes_")) }
    private val prefs = context.applicationContext.getSharedPreferences(storageName, Context.MODE_PRIVATE)
    private val aad = "$alias:official-image-key".toByteArray(Charsets.UTF_8)

    fun read(): String? = synchronized(lock) {
        val encoded = prefs.getString("encrypted", null) ?: return@synchronized null
        try {
            require(encoded.length <= 8192)
            val parts = encoded.split(':')
            require(parts.size == 2)
            val iv = Base64.decode(parts[0], Base64.NO_WRAP)
            require(iv.size == 12)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
            cipher.updateAAD(aad)
            LocalMemoryCodec.utf8(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP))).also { AgnesImageProtocol.checkKey(it) }
        } catch (_: Exception) {
            throw IllegalStateException("本机图像服务密钥无法读取，请重新输入；聊天和恋人绑定已保留")
        }
    }

    fun write(apiKey: String) = synchronized(lock) {
        AgnesImageProtocol.checkKey(apiKey)
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key())
            cipher.updateAAD(aad)
            val encrypted = cipher.doFinal(LocalMemoryCodec.bytes(apiKey))
            val value = Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(encrypted, Base64.NO_WRAP)
            check(prefs.edit().putString("encrypted", value).commit())
        } catch (_: Exception) { throw IllegalStateException("无法保存本机加密图像服务密钥") }
    }

    fun clear() = synchronized(lock) {
        check(prefs.edit().remove("encrypted").commit()) { "无法清除本机图像服务密钥" }
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build())
            generateKey()
        }
    }
}
