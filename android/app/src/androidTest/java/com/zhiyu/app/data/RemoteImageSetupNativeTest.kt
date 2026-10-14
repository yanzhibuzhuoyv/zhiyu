package com.zhiyu.app.data

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class RemoteImageSetupNativeTest {
    @Test fun defaultVisualProfileReflectsTheIndependentImageServiceConfiguration()=runBlocking {
        val base=InstrumentationRegistry.getInstrumentation().targetContext
        val context=NativeV4Context(base)
        try {
            val repo=LocalVisualRepository(context)
            val chat=LocalMediaStore.identifier()
            val keys=RemoteImageCredentialStore(context)
            assertFalse(repo.profile(chat).generation.configured)
            keys.write("synthetic-image-service-key")
            assertTrue(repo.profile(chat).generation.configured)
            keys.clear()
            assertFalse(repo.profile(chat).generation.configured)
        } finally {context.close();val root=context.filesDir.canonicalFile
            check(root.parentFile==base.filesDir.canonicalFile&&root.name.startsWith("v4_"));check(root.deleteRecursively())}
    }
}
