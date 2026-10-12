package com.zhiyu.app.data.local.qwen

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.*
import android.os.Process
import android.util.Log
import com.zhiyu.app.domain.QwenResourceRules
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.UUID

class QwenProcessDeferred(message: String = "本机制图进程停止尚未确认，稍后自动检查服务") : IllegalStateException(message)

/** Main process never initializes a JNI binding. Each stage must observe remote Binder death before returning. */
class QwenNativeClient(context: Context) {
    private val app = context.applicationContext ?: context
    private val store = QwenImageJobStore(app)
    companion object {
        private val stageLock = Mutex()
        private val unconfirmed = mutableSetOf<String>()
    }

    suspend fun run(job: QwenImageJob, stage: String, output: File): QwenNativeResult = stageLock.withLock {
        requireAvailable(stage)
        recoverUnconfirmed()
        val relative = withContext(Dispatchers.IO) {
            require(store.read(job.id) == job) { "制图任务已变化或没有登记" }
            QwenNativeServiceProtocol.requireState(stage, job.state)
            store.relative(job, output).also { if (stage != "inspect") require(!output.exists()) }
        }
        if (stage != "inspect") requireStart(stage)
        val connection = Connection(stage)
        withClosedProcess(connection) {
            connection.start()
            connection.awaitHello()
            connection.execute(job.id, relative)
            awaitResult(connection, stage)
        }
    }

    /** A no-model diagnostic can also clear quarantine after it confirms the exact process has exited. */
    suspend fun hello(stage: String): QwenNativeHello = stageLock.withLock {
        requireAvailable(stage)
        helloUnlocked(stage)
    }

    private suspend fun helloUnlocked(stage: String): QwenNativeHello {
        val connection = Connection(stage)
        return withClosedProcess(connection) { connection.start(); connection.awaitHello() }
    }

    /** Does not re-enter the stage Mutex or start a model; a genuine identity/death handshake is required. */
    private suspend fun recoverUnconfirmed() {
        for (suffix in unconfirmed.toList()) {
            try { helloUnlocked(if (suffix == ":qwen_text") "text" else "inspect") }
            catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                if (suffix in unconfirmed) throw QwenProcessDeferred()
                throw failure
            }
        }
        if (unconfirmed.isNotEmpty()) throw QwenProcessDeferred()
    }

    private suspend fun <T> withClosedProcess(connection: Connection, action: suspend () -> T): T {
        try { return action() }
        finally {
            val confirmed = withContext(NonCancellable) {
                try { connection.shutdown() }
                catch (failure: Exception) { Log.w("QwenNativeClient", "Native shutdown confirmation failed", failure); false }
            }
            if (confirmed) {
                if (connection.deathConfirmed) unconfirmed.remove(connection.suffix)
            }
            else {
                unconfirmed.add(connection.suffix)
                throw QwenProcessDeferred()
            }
        }
    }

    private fun requireAvailable(stage: String) {
        QwenNativeServiceProtocol.requireStage(stage)
        require(QwenRelease.supported()) { "本机生图需要 Android 10 及 64 位设备" }
    }
    private fun requireStart(stage: String) {
        if (!QwenResourceRules.canStart(stage, QwenDeviceResources(app).read())) throw QwenResourceDeferred()
    }

    private suspend fun awaitResult(connection: Connection, stage: String): QwenNativeResult = coroutineScope {
        val monitor = if (stage == "inspect") null else launch(Dispatchers.Default) {
            while (isActive) {
                delay(2000)
                try {
                    if (QwenResourceRules.mustStop(QwenDeviceResources(app).read())) {
                        connection.fail(QwenResourceDeferred())
                        connection.stop()
                        break
                    }
                } catch (failure: Exception) {
                    if (failure is CancellationException) throw failure
                    connection.fail(QwenResourceDeferred("设备资源状态暂时无法确认"))
                    connection.stop()
                    break
                }
            }
        }
        try {
            withTimeout(QwenNativeServiceProtocol.budgetMs(stage)) { connection.result.await() }
        } catch (_: TimeoutCancellationException) { error("本机制图阶段等待超时，已请求停止") }
        finally { monitor?.cancelAndJoin() }
    }

    private inner class Connection(private val stage: String) {
        val suffix = QwenNativeServiceProtocol.suffix(stage)
        private val request = UUID.randomUUID().toString()
        private val greeting = CompletableDeferred<QwenNativeHello>()
        val result = CompletableDeferred<QwenNativeResult>()
        private val death = CompletableDeferred<Unit>()
        val deathConfirmed: Boolean get() = remote != null && death.isCompleted
        private var bound = false
        private var everBound = false
        @Volatile private var remote: Messenger? = null
        @Volatile private var executing = false
        private val deathRecipient = IBinder.DeathRecipient { died() }
        private val replies = Messenger(object : Handler(Looper.getMainLooper()) {
            override fun handleMessage(message: Message) {
                try { receive(message) }
                catch (failure: Exception) { fail(failure) }
            }
        })
        private val service = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                try {
                    remote = Messenger(binder)
                    binder.linkToDeath(deathRecipient, 0)
                    send(QwenNativeServiceProtocol.HELLO)
                } catch (failure: RemoteException) {
                    if (!binder.isBinderAlive) died() else fail(failure)
                }
            }
            override fun onServiceDisconnected(name: ComponentName) {
                if (remote?.binder?.isBinderAlive == false) died()
                else fail(IllegalStateException("本机制图服务连接中断"))
            }
            override fun onBindingDied(name: ComponentName) {
                if (remote?.binder?.isBinderAlive == false) died()
                else fail(IllegalStateException("本机制图服务绑定已失效"))
            }
            override fun onNullBinding(name: ComponentName) { fail(IllegalStateException("本机制图服务没有提供通信通道")) }
        }

        suspend fun start() = withContext(Dispatchers.Main.immediate) {
            val component = if (stage == "text") QwenTextService::class.java else QwenImageService::class.java
            bound = app.bindService(Intent(app, component), service, Context.BIND_AUTO_CREATE)
            everBound = bound
            check(bound) { "无法连接本机制图服务" }
        }
        suspend fun awaitHello(): QwenNativeHello = try {
            withTimeout(QwenNativeServiceProtocol.CONNECT_TIMEOUT_MS) { greeting.await() }
        } catch (_: TimeoutCancellationException) { error("本机制图服务未及时建立连接") }

        suspend fun execute(job: String, relative: String) = withContext(Dispatchers.Main.immediate) {
            executing = true
            send(QwenNativeServiceProtocol.RUN, Bundle().apply {
                putString("job", job); putString("stage", stage); putString("output", relative)
            })
        }
        fun fail(failure: Throwable) { greeting.completeExceptionally(failure); result.completeExceptionally(failure) }
        private fun died() {
            death.complete(Unit)
            fail(IllegalStateException("本机制图进程已退出，未完成的阶段不会当成成功"))
        }

        private fun receive(message: Message) {
            require(message.sendingUid == Process.myUid() && message.data.getString("request") == request) { "制图回执身份不匹配" }
            val payload = message.data
            val pid = payload.getInt("pid", -1)
            val uid = payload.getInt("uid", -1)
            val process = payload.getString("process")
            require(pid > 0 && pid != Process.myPid() && uid == app.applicationInfo.uid && process == app.packageName + suffix) {
                "制图回执不属于预期隔离服务"
            }
            when (message.what) {
                QwenNativeServiceProtocol.HELLO_REPLY -> {
                    val identity = payload.getString("identity")
                    require(identity == QwenRelease.IDENTITY && !greeting.isCompleted)
                    greeting.complete(QwenNativeHello(requireNotNull(identity), pid, uid, requireNotNull(process)))
                }
                QwenNativeServiceProtocol.RESULT -> {
                    require(executing && greeting.isCompleted)
                    val tokens = payload.getInt("tokens", -1)
                    val metadata = payload.getString("metadata")
                    require(metadata == null || metadata.length in 1..QwenNativeServiceProtocol.MAX_METADATA)
                    require(if (stage == "text") tokens in 1..498 && metadata == null else tokens == 0)
                    require(stage !in setOf("sample", "inspect") || metadata != null)
                    result.complete(QwenNativeResult(tokens, metadata))
                }
                QwenNativeServiceProtocol.ERROR -> {
                    val failure = payload.getString("failure")?.take(256) ?: "本机制图阶段未完成"
                    fail(if (payload.getBoolean("deferred")) QwenResourceDeferred() else IllegalStateException(failure))
                }
                else -> error("未知制图服务回执")
            }
        }

        private fun send(what: Int, payload: Bundle = Bundle()) {
            payload.putString("request", request)
            requireNotNull(remote) { "制图服务没有连接" }.send(Message.obtain(null, what).apply { data = payload; replyTo = replies })
        }
        suspend fun stop() = withContext(Dispatchers.Main.immediate) {
            if (remote?.binder?.isBinderAlive == true) {
                try { send(QwenNativeServiceProtocol.STOP) }
                catch (failure: RemoteException) {
                    Log.w("QwenNativeClient", "Owned native process did not receive STOP", failure)
                    if (remote?.binder?.isBinderAlive == false) died()
                }
            }
        }
        suspend fun shutdown(): Boolean {
            withContext(Dispatchers.Main.immediate) {
                try { stop() } finally {
                    if (bound) {
                        try { app.unbindService(service) }
                        catch (failure: IllegalArgumentException) { Log.w("QwenNativeClient", "Native service already unbound", failure) }
                        bound = false
                    }
                }
            }
            if (remote == null) return !everBound
            if (remote?.binder?.isBinderAlive == false) died()
            val confirmed = withTimeoutOrNull(QwenNativeServiceProtocol.STOP_TIMEOUT_MS) { death.await(); true } ?: false
            if (confirmed) {
                try { remote?.binder?.unlinkToDeath(deathRecipient, 0) } catch (_: NoSuchElementException) { }
            }
            return confirmed
        }
    }
}
