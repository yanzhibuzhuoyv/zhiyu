package com.zhiyu.app.data

import com.zhiyu.app.domain.ChatPhotoRules

internal data class OcPhotoConfirmation(val snapshot: OcPhotoIdentitySnapshot, val photoSha: String,
    val confirmed: Boolean = true) {
    init { ChatPhotoRules.digest(photoSha) }
}
