package com.zhiyu.app.data

import android.os.Bundle
import androidx.test.platform.app.InstrumentationRegistry

/** Only synthetic metrics. Explicit status packets keep reports out of unrestricted logcat dumps. */
internal fun nativeProbeReport(text: String) {
    require(text.length <= 16384)
    InstrumentationRegistry.getInstrumentation().sendStatus(2, Bundle().apply { putString("stream", "$text\n") })
}
