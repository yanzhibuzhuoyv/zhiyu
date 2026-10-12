package com.zhiyu.app.data.local.qwen

/** Fixed CPU 512/Euler8/CFG1; sample-only releases DiT before separate strict-checkpoint decoding. */
object QwenImageNative {
    const val LIBRARY_NAME = "zhiyu_qwen_image_jni"
    init { System.loadLibrary(LIBRARY_NAME) }
    @JvmStatic external fun sample(dit: String, vae: String, conditions: String, tokens: Int, seed: Long,
        unusedPng: String, newCheckpoint: String): Int
    @JvmStatic external fun decode(vae: String, checkpoint: String, newPng: String): Int
    @JvmStatic external fun inspectCP(checkpoint: String): String
    @JvmStatic external fun runtimeIdentity(): String
}
