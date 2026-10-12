package com.zhiyu.app.ui

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.app.ActivityOptionsCompat
import androidx.test.platform.app.InstrumentationRegistry
import com.zhiyu.app.ui.screens.QwenModelDialog
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.UUID

class QwenSinglePackDialogNativeTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val base = InstrumentationRegistry.getInstrumentation().targetContext
    private val owned = File(base.cacheDir, "single-pack-dialog-${UUID.randomUUID()}").canonicalFile.apply { check(mkdirs()) }
    private val context = object : ContextWrapper(base) {
        override fun getApplicationContext(): Context = this
        override fun getFilesDir(): File = owned
    }
    private var saved = false
    private var requested: Intent? = null
    private var selectionCode = -1
    private val registry = object : ActivityResultRegistry() {
        override fun <I, O> onLaunch(code: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) {
            selectionCode = code
            requested = contract.createIntent(context, input)
        }
    }

    @After fun cleanup() {
        require(owned.parentFile == base.cacheDir.canonicalFile && !Files.isSymbolicLink(owned.toPath()))
        Files.walk(owned.toPath()).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { path ->
            require(path.startsWith(owned.toPath())); Files.deleteIfExists(path)
        } }
    }

    private fun dialog() {
        val owner = object : ActivityResultRegistryOwner { override val activityResultRegistry = registry }
        compose.setContent {
            CompositionLocalProvider(LocalContext provides context, LocalActivityResultRegistryOwner provides owner) {
                QwenModelDialog(onSaved = { saved = true }, onDismiss = {})
            }
        }
    }

    @Test fun aSingleModelFileIsThePrimaryImportChoice() {
        dialog()
        compose.onNodeWithTag("qwen_import_pack_file").assertExists().assertIsEnabled()
        compose.onNodeWithText("导入模型包").assertExists()
        compose.onNodeWithTag("qwen_pack_status").assertTextEquals("还没有导入学生模型包")
        assertFalse(saved)
    }

    @Test fun thePrimaryChoiceRequestsOneOpenableDocumentRatherThanADirectory() {
        dialog()
        compose.onNodeWithTag("qwen_import_pack_file").performClick()
        compose.runOnIdle {
            assertEquals(Intent.ACTION_OPEN_DOCUMENT, requested?.action)
            assertTrue(requested?.hasCategory(Intent.CATEGORY_OPENABLE) == true)
            assertFalse(requested?.getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE, false) == true)
        }
    }

    @Test fun invalidSelectionShowsAnErrorAndAllowsRetryWithoutReportingInstalled() {
        dialog()
        compose.onNodeWithTag("qwen_import_pack_file").performClick()
        compose.runOnIdle { registry.dispatchResult(selectionCode, Uri.parse("file:///not-an-authorized-model.zyqwen")) }
        compose.waitUntil(5000) { compose.onAllNodesWithText("请选择模型包文件").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("qwen_import_pack_file").assertIsEnabled()
        compose.onNodeWithTag("qwen_pack_status").assertTextEquals("还没有导入学生模型包")
        assertFalse(saved)
    }
}
