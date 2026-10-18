package com.zhiyu.app.data

import android.database.sqlite.SQLiteDatabase
import android.os.Bundle
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import com.zhiyu.app.domain.ChatPhotoRules
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.time.Instant
import java.time.ZoneId

/** Explicit read-only USB inspection. Never emits credentials, prompts, or original chat text. */
class UsbFeedbackDiagnosticsNativeTest {
    @Test fun readCurrentDeviceCapabilitiesWithoutChangingUserState() = runBlocking {
        val runner = InstrumentationRegistry.getInstrumentation()
        assumeTrue(InstrumentationRegistry.getArguments().getString("zhiyu_usb_diagnostics") == "true")
        fun step(value: String) = runner.sendStatus(2, Bundle().apply { putString("zhiyu_usb_step", value) })
        step("context")
        val app = runner.targetContext
        step("chat-credentials")
        val connection = appCredentialStore(app).read()
        step("binding")
        val binding = appCredentialStore(app).readBinding()
        step("image-credentials")
        val image = RemoteImageCredentialStore(app).configuration()
        step("clock")
        val report = JSONObject().put("deviceTime", Instant.now().toString()).put("zone", ZoneId.systemDefault().id)
            .put("chatKeyReadable", connection != null).put("bindingReadable", binding != null)
            .put("provider", connection?.providerId).put("model", connection?.model)
            .put("nativeVision", connection?.let { ChatPhotoRules.nativeVision(it.providerId, it.model) } == true)
            .put("imageKeyReadable", image != null).put("imageModel", image?.model)
            .put("networkTime", if (android.os.Build.VERSION.SDK_INT >= 33)
                try { SystemClock.currentNetworkTimeClock().instant().toString() } catch (_: java.time.DateTimeException) { "unavailable" }
                else "unsupported")
        val archive = app.getDatabasePath("zhiyu_local_v1.sqlite3")
        step("archive")
        if (archive.isFile) SQLiteDatabase.openDatabase(archive.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery("SELECT id FROM conversations ORDER BY updated_at DESC LIMIT 1", null).use { rows ->
                if (rows.moveToFirst()) {
                    val chat = rows.getString(0)
                    val profile = LocalVisualRepository(app).profile(chat)
                    report.put("visualMode", profile.mode).put("visualVersion", profile.visualVersion)
                        .put("hasReference", profile.referenceId != null)
                    val packages = File(app.filesDir, "planar-v1").walkTopDown().filter { it.isFile }.toList()
                    report.put("planarFiles", packages.size).put("planarBytes", packages.sumOf { it.length() })
                }
            }
        }
        val ledger = app.getDatabasePath("local_image_jobs.sqlite3")
        step("ledger")
        if (ledger.isFile) SQLiteDatabase.openDatabase(ledger.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            val states = JSONObject()
            db.rawQuery("SELECT kind,state,COUNT(*) FROM jobs GROUP BY kind,state", null).use { rows ->
                while (rows.moveToNext()) states.put("${rows.getString(0)}:${rows.getString(1)}", rows.getInt(2))
            }
            report.put("generationStates", states)
        }
        runner.sendStatus(2, Bundle().apply { putString("zhiyu_usb_safe_diagnostics", report.toString()) })
    }
}
