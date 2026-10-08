package com.zhiyu.app.data

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import com.zhiyu.app.domain.AuthSession
import com.zhiyu.app.domain.CompanionBinding
import com.zhiyu.app.domain.CompanionProfile
import com.zhiyu.app.domain.SavedConnection
import org.junit.Assert.*
import org.junit.Test
import java.security.KeyStore
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Only UUID-scoped test preferences/Keystore aliases; never opens the installed user's credential store. */
class SecureCredentialCasTest {
    private fun stores(test: (SecureCredentialStore, SecureCredentialStore) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val id = UUID.randomUUID().toString()
        val name = "cas_test_$id"
        val alias = "cas_test_key_$id"
        try { test(SecureCredentialStore(context, name, alias), SecureCredentialStore(context, name, alias)) }
        finally {
            context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit()
            KeyStore.getInstance("AndroidKeyStore").apply { load(null); if (containsAlias(alias)) deleteEntry(alias) }
        }
    }

    private val initial = SavedConnection("https://synthetic.invalid", "deepseek", "synthetic-key", "fixed-model",
        AuthSession("old-synthetic-session", "deepseek", "fixed-model", 1000))

    @Test fun aDifferentStoreInstanceCannotCommitAfterCredentialClear() = stores { first, second ->
        val binding = CompanionBinding("deepseek", "fixed-model", CompanionProfile(style = "warm_v2"))
        first.writeBinding(binding)
        first.write(initial)
        val expected = second.read()
        first.clear()
        assertFalse(second.compareAndWrite(expected, initial.copy(session = initial.session!!.copy(token = "stale-session"))))
        assertNull(first.read())
        assertEquals(binding, second.readBinding())
    }

    @Test fun competingUpdatesPreserveTheFirstCommittedSession() = stores { first, second ->
        first.write(initial)
        val newer = initial.copy(session = initial.session!!.copy(token = "newer-session"))
        assertTrue(first.compareAndWrite(initial, newer))
        assertFalse(second.compareAndWrite(initial, initial.copy(apiKey = "stale-key")))
        assertEquals(newer, second.read())
    }

    @Test fun concurrentClearAndCasNeverLeaveCredentialsRestored() = stores { first, second ->
        val pool = Executors.newFixedThreadPool(2)
        try {
            repeat(24) {
                first.write(initial)
                val start = CountDownLatch(1)
                val cas = pool.submit { start.await(); second.compareAndWrite(initial, initial.copy(apiKey = "synthetic-updated")) }
                val clear = pool.submit { start.await(); first.clear() }
                start.countDown()
                cas.get(10, TimeUnit.SECONDS)
                clear.get(10, TimeUnit.SECONDS)
                assertNull(first.read())
            }
        } finally { pool.shutdownNow() }
    }
}
