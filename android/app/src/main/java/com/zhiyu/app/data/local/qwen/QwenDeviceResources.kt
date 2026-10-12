package com.zhiyu.app.data.local.qwen

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.os.StatFs
import com.zhiyu.app.domain.QwenResources

class QwenResourceDeferred(message: String = "本机制图等待设备冷却或释放内存") : IllegalStateException(message)

/** App-UID APIs only; no adb/dumpsys or cached shell battery values. */
class QwenDeviceResources(private val context: Context) {
    fun read(): QwenResources {
        val memory = ActivityManager.MemoryInfo()
        context.getSystemService(ActivityManager::class.java).getMemoryInfo(memory)
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val temp = battery?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)?.takeIf { it in 1..600 }
        val thermal = if (Build.VERSION.SDK_INT >= 29) context.getSystemService(PowerManager::class.java).currentThermalStatus else null
        return QwenResources(temp, thermal, memory.availMem, memory.lowMemory, StatFs(context.filesDir.path).availableBytes)
    }
}
