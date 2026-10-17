package com.zhiyu.app.data

/** Fixed 16 kHz mono PCM. Implementations own capture resources until close. */
internal interface VoiceCaptureInput : AutoCloseable {
    fun start()
    fun read(maximumFrames: Int): FloatArray
    fun stop()
}

interface VoiceCaptureRecognition : AutoCloseable {
    fun accept(samples: FloatArray): String
    fun endpoint(): Boolean
    fun finish(): String
}
