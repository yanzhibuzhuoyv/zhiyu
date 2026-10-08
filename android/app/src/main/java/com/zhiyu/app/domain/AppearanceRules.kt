package com.zhiyu.app.domain

import java.net.URI
import kotlin.math.roundToInt

enum class AppearanceTarget(val label: String) {
    USER_AVATAR("我的头像"), COMPANION_AVATAR("恋人头像"), BACKGROUND("聊天背景"),
}

data class AppearanceSelection(
    val userAvatar: String? = null,
    val companionAvatar: String? = null,
    val background: String? = null,
) {
    operator fun get(target: AppearanceTarget) = when (target) {
        AppearanceTarget.USER_AVATAR -> userAvatar
        AppearanceTarget.COMPANION_AVATAR -> companionAvatar
        AppearanceTarget.BACKGROUND -> background
    }

    fun with(target: AppearanceTarget, uri: String?) = when (target) {
        AppearanceTarget.USER_AVATAR -> copy(userAvatar = uri)
        AppearanceTarget.COMPANION_AVATAR -> copy(companionAvatar = uri)
        AppearanceTarget.BACKGROUND -> copy(background = uri)
    }
}

data class ImageDimensions(val width: Int, val height: Int)

object AppearanceRules {
    const val MAX_IMAGE_BYTES = 25 * 1024 * 1024
    private const val MAX_SOURCE_PIXELS = 120_000_000L

    fun documentUri(value: String): String {
        require(value.none { it.code < 32 }) { "图片地址无效，请重新选择" }
        val uri = try { URI(value) } catch (_: Exception) { throw IllegalArgumentException("图片地址无效，请重新选择") }
        val authority = uri.rawAuthority.orEmpty()
        require(uri.scheme == "content" && authority.isNotBlank() && uri.userInfo == null && '@' !in authority && uri.fragment == null) {
            "请通过系统文件选择器选择图片"
        }
        return value
    }

    fun decodedSize(width: Int, height: Int, target: AppearanceTarget): ImageDimensions {
        require(width in 1..32768 && height in 1..32768 && width.toLong() * height <= MAX_SOURCE_PIXELS) {
            "图片尺寸过大或格式不受支持，请换一张图片"
        }
        val limit = if (target == AppearanceTarget.BACKGROUND) 2048 else 512
        val scale = minOf(1.0, limit.toDouble() / maxOf(width, height))
        return ImageDimensions(maxOf(1, (width * scale).roundToInt()), maxOf(1, (height * scale).roundToInt()))
    }
}
