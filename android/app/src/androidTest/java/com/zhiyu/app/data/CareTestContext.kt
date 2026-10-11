package com.zhiyu.app.data

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import java.util.UUID

/** Instrumentation runs as the target UID; isolated names avoid both cross-UID writes and app preferences. */
internal class CareTestContext(base: Context) : ContextWrapper(base) {
    private val prefix = "care_synthetic_${UUID.randomUUID()}_"
    private val names = mutableSetOf<String>()
    override fun getApplicationContext(): Context = this
    override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
        val scoped = prefix + name
        names.add(scoped)
        return baseContext.getSharedPreferences(scoped, mode)
    }
    fun cleanup() { names.forEach { baseContext.deleteSharedPreferences(it) } }
}
