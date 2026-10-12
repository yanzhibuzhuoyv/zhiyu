package com.zhiyu.app.data.local.qwen

/** Runs only inside the isolated text service process; cancellation is owned process termination. */
object QwenTextNative {
    const val LIBRARY_NAME = "zhiyu_qwen_text_jni"
    init { System.loadLibrary(LIBRARY_NAME) }
    @JvmStatic external fun encode(model: String, adapter: String, prompt: ByteArray, newConditions: String): Int
    @JvmStatic external fun runtimeIdentity(): String
}
