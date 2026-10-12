package com.zhiyu.app.data.local.qwen

import android.app.Application
import android.app.Service
import android.content.Intent
import android.os.*
import android.os.Process
import android.util.Log
import com.zhiyu.app.domain.LocalMemoryCodec
import com.zhiyu.app.domain.QwenResourceRules
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.util.concurrent.Executors

class QwenTextService : QwenNativeService(":qwen_text")
class QwenImageService : QwenNativeService(":qwen_image")

/** Only a correctly isolated App-UID process may load JNI or terminate itself. */
abstract class QwenNativeService(private val suffix: String) : Service() {
    private data class Session(val id: String, val replies: Messenger, val death: IBinder.DeathRecipient,
        var ran: Boolean = false, @Volatile var ready: Boolean = false)
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private var session: Session? = null
    private var stopping = false
    private val incoming = Messenger(object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(message: Message) { dispatch(message) }
    })

    override fun onBind(intent: Intent): IBinder = incoming.binder
    override fun onUnbind(intent: Intent): Boolean { terminateOwn(); return false }
    override fun onDestroy() {
        session?.let { try { it.replies.binder.unlinkToDeath(it.death, 0) } catch (_: NoSuchElementException) { } }
        worker.shutdownNow()
        terminateOwn()
        super.onDestroy()
    }

    private fun dispatch(message: Message) {
        if (message.sendingUid != Process.myUid() || stopping) return
        try {
            val id = QwenNativeServiceProtocol.request(requireNotNull(message.data.getString("request")))
            val replies = requireNotNull(message.replyTo) { "缺少制图服务回执通道" }
            when (message.what) {
                QwenNativeServiceProtocol.HELLO -> hello(id, replies)
                QwenNativeServiceProtocol.RUN -> runStage(id, replies, message.data)
                QwenNativeServiceProtocol.STOP -> {
                    requireSession(id, replies)
                    terminateOwn()
                }
                else -> error("未知制图服务消息")
            }
        } catch (failure: Exception) {
            Log.w("QwenNativeService", "Rejected native service request: ${failure.javaClass.simpleName}")
            val own = session
            if (own != null && message.data.getString("request") == own.id && message.replyTo?.binder == own.replies.binder) {
                sendFailure(own, failure)
            }
        }
    }

    private fun hello(id: String, replies: Messenger) {
        require(session == null) { "此制图服务已经属于另一请求" }
        val death = IBinder.DeathRecipient { main.post { terminateOwn() } }
        replies.binder.linkToDeath(death, 0)
        val owned = Session(id, replies, death).also { session = it }
        worker.execute {
            try {
                requireOwnProcess()
                val identity = if (suffix == ":qwen_text") QwenTextNative.runtimeIdentity() else QwenImageNative.runtimeIdentity()
                check(identity == QwenRelease.IDENTITY) { "本机制图运行时版本不一致" }
                owned.ready = true
                send(owned, QwenNativeServiceProtocol.HELLO_REPLY, Bundle().apply { putString("identity", identity) })
            } catch (failure: Exception) { sendFailure(owned, failure) }
            catch (failure: LinkageError) { sendFailure(owned, failure) }
        }
    }

    private fun runStage(id: String, replies: Messenger, data: Bundle) {
        val owned = requireSession(id, replies)
        require(owned.ready && !owned.ran) { "运行时尚未验证或此进程已经执行一个阶段" }
        val stage = requireNotNull(data.getString("stage"))
        QwenNativeServiceProtocol.requireStage(stage)
        require(QwenNativeServiceProtocol.suffix(stage) == suffix) { "制图阶段与隔离进程不匹配" }
        val jobId = QwenNativeServiceProtocol.jobId(requireNotNull(data.getString("job")))
        val relative = requireNotNull(data.getString("output"))
        require(relative.length <= 240) { "制图输出标识过长" }
        owned.ran = true
        worker.execute {
            try {
                requireOwnProcess()
                val result = executeStage(jobId, stage, relative)
                send(owned, QwenNativeServiceProtocol.RESULT, Bundle().apply {
                    putInt("tokens", result.tokens); result.metadata?.let { putString("metadata", it) }
                })
            } catch (failure: Exception) { sendFailure(owned, failure) }
            catch (failure: LinkageError) { sendFailure(owned, failure) }
        }
    }

    private fun executeStage(id: String, stage: String, relative: String): QwenNativeResult {
        val store = QwenImageJobStore(this)
        val job = store.read(id) ?: error("制图任务没有登记")
        QwenNativeServiceProtocol.requireState(stage, job.state)
        val output = store.file(job, relative)
        if (stage == "inspect") {
            require(relative == job.checkpoint) { "只能检查此任务已保存的采样结果" }
            source(output)
            val metadata = checkedMetadata(QwenImageNative.inspectCP(output.path), job)
            require(store.read(id) == job) { "检查期间制图任务已变化" }
            return QwenNativeResult(metadata = metadata)
        }
        require(QwenResourceRules.canStart(stage, QwenDeviceResources(this).read())) { "resource_deferred" }
        newOutput(output)
        val models = modelFiles()
        val result = when (stage) {
            "text" -> encode(job, output, models)
            "sample" -> sample(job, output, models, store)
            "decode" -> decode(job, output, models, store)
            else -> error("未知制图阶段")
        }
        require(store.read(id) == job) { "制图期间任务已变化，不能发布旧结果" }
        return result
    }

    private fun encode(job: QwenImageJob, output: File, models: Map<QwenFileRole, File>): QwenNativeResult {
        require(job.prompt.isNotBlank() && job.condition == null && output.extension == "f32")
        val prompt = LocalMemoryCodec.bytes(job.prompt)
        require(prompt.size <= 4000)
        protectOutput(output, models.values)
        val tokens = QwenTextNative.encode(models.getValue(QwenFileRole.STUDENT).path,
            models.getValue(QwenFileRole.ADAPTER).path, prompt, output.path)
        check(tokens in 1..498 && output.length() == tokens.toLong() * 4096 * 4) { "文本条件结果不完整" }
        return QwenNativeResult(tokens = tokens)
    }

    private fun sample(job: QwenImageJob, output: File, models: Map<QwenFileRole, File>, store: QwenImageJobStore): QwenNativeResult {
        require(job.tokens in 1..498 && output.extension in setOf("qwenlatent", "zqcp"))
        val conditions = source(store.file(job, requireNotNull(job.condition)))
        require(conditions.length() == job.tokens.toLong() * 4096 * 4) { "文本条件大小不一致" }
        protectOutput(output, models.values + conditions)
        val unused = File(output.parentFile, "unused-sample.png")
        newOutput(unused)
        check(QwenImageNative.sample(models.getValue(QwenFileRole.DIT).path, models.getValue(QwenFileRole.VAE).path,
            conditions.path, job.tokens, job.seed + job.attempt, unused.path, output.path) == 0) { "本机采样未完成" }
        check(!unused.exists()) { "采样阶段不应生成图片" }
        return QwenNativeResult(metadata = checkedMetadata(QwenImageNative.inspectCP(output.path), job))
    }

    private fun decode(job: QwenImageJob, output: File, models: Map<QwenFileRole, File>, store: QwenImageJobStore): QwenNativeResult {
        require(output.extension == "png")
        val checkpoint = source(store.file(job, requireNotNull(job.checkpoint)))
        checkedMetadata(QwenImageNative.inspectCP(checkpoint.path), job)
        protectOutput(output, models.values + checkpoint)
        check(QwenImageNative.decode(models.getValue(QwenFileRole.VAE).path, checkpoint.path, output.path) == 0 && output.length() > 0) {
            "本机图片解码未完成"
        }
        return QwenNativeResult()
    }

    private fun checkedMetadata(raw: String, job: QwenImageJob): String {
        require(raw.length in 1..QwenNativeServiceProtocol.MAX_METADATA)
        val value = JSONObject(raw)
        require(value.getBoolean("crc_verified") && value.getBoolean("finite") && value.getLong("seed") == job.seed + job.attempt &&
            value.getInt("steps") == 8 && value.getString("stage") == "diffusion_normalized" && value.getInt("bytes") == 262288)
        return raw
    }

    private fun modelFiles(): Map<QwenFileRole, File> {
        val root = QwenPackStore(this).installed() ?: error("学生模型包尚未导入")
        return QwenRelease.manifest(this).files.associate { spec ->
            val file = source(File(root, spec.path))
            require(file.length() == spec.bytes && file.toPath().startsWith(root.toPath()))
            spec.role to file
        }
    }

    private fun source(file: File): File {
        require(file.isFile && !Files.isSymbolicLink(file.toPath()) && file.canonicalFile == file.absoluteFile) { "制图输入路径无效" }
        return file
    }
    private fun newOutput(file: File) {
        require(!file.exists() && !Files.isSymbolicLink(file.toPath()) && file.canonicalFile == file.absoluteFile &&
            file.parentFile?.isDirectory == true) { "制图输出已存在或路径无效" }
    }
    private fun protectOutput(output: File, inputs: Collection<File>) {
        val parent = output.parentFile.toPath()
        inputs.forEach { require(!parent.startsWith(it.parentFile.toPath())) { "输出不能进入模型或前一阶段输入目录" } }
    }
    private fun requireSession(id: String, replies: Messenger): Session = requireNotNull(session).also {
        require(it.id == id && it.replies.binder == replies.binder) { "停止或执行请求不属于当前会话" }
    }
    private fun requireOwnProcess() {
        require(Build.VERSION.SDK_INT >= 28 && Application.getProcessName() == packageName + suffix &&
            Process.myUid() == applicationInfo.uid) { "制图服务未处于自己的 App-UID 隔离进程" }
    }
    private fun terminateOwn() {
        if (stopping) return
        stopping = true
        if (Build.VERSION.SDK_INT >= 28 && Application.getProcessName() == packageName + suffix && Process.myUid() == applicationInfo.uid) {
            Process.killProcess(Process.myPid())
        } else { Log.e("QwenNativeService", "Refused to terminate a process without exact App-UID ownership"); stopSelf() }
    }
    private fun sendFailure(owned: Session, failure: Throwable) = send(owned, QwenNativeServiceProtocol.ERROR, Bundle().apply {
        putString("failure", (failure.message ?: "本机制图阶段未完成").take(256))
        putBoolean("deferred", failure.message == "resource_deferred")
    })
    private fun send(owned: Session, what: Int, payload: Bundle) {
        payload.putString("request", owned.id); payload.putInt("pid", Process.myPid()); payload.putInt("uid", Process.myUid())
        payload.putString("process", if (Build.VERSION.SDK_INT >= 28) Application.getProcessName() else "unsupported")
        try { owned.replies.send(Message.obtain(null, what).apply { data = payload }) }
        catch (_: RemoteException) { main.post { terminateOwn() } }
    }
}
