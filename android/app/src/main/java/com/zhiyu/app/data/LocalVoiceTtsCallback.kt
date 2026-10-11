package com.zhiyu.app.data

/**
 * Sherpa JNI resolves invoke(float[]) returning boxed Integer on the concrete class.
 * A Kotlin indy lambda only exposes an Object bridge and makes that native lookup abort.
 * Keep this named class and its methods in R8; returning 0 cancels native synthesis.
 */
class LocalVoiceTtsCallback(private val active: () -> Boolean) : (FloatArray) -> Int {
    override fun invoke(samples: FloatArray): Int = if (active()) 1 else 0
}
