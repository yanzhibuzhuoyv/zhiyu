package com.zhiyu.app.data

import com.zhiyu.app.domain.LocalMemoryCodec
import com.zhiyu.app.domain.QueuedTextInput
import com.zhiyu.app.domain.QueuedTextSubmission

internal data class QueuedBatchOriginals(val submitted: List<QueuedTextSubmission>, val existed: Boolean)

internal fun queuedBatchFingerprint(inputs: List<QueuedTextInput>): String = LocalMemoryCodec.sha(
    inputs.joinToString("\n") { "${it.requestId}:${LocalMemoryCodec.sha(LocalMemoryCodec.bytes(it.content))}" }.toByteArray(Charsets.UTF_8))
