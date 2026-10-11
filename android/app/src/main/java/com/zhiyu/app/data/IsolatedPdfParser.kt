package com.zhiyu.app.data

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.*
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Host process never passes private paths, credentials or chats to the untrusted decoder. */
internal object IsolatedPdfParser {
    const val REQUEST = 1
    const val TEXT = PdfHostCollector.TEXT
    const val COMPLETE = PdfHostCollector.COMPLETE
    const val FAILURE = PdfHostCollector.FAILURE
    const val MAX_TEXT = PdfHostCollector.MAX_TEXT
    fun parse(context: Context, bytes: ByteArray): String {
        check(Looper.myLooper() != Looper.getMainLooper()) { "请在后台读取 PDF" }
        return PdfParseSession(context, bytes).parse()
    }
}

/** One parse owns its pipe and Binder callbacks; no session can reuse another result. */
private class PdfParseSession(context: Context, private val bytes: ByteArray) {
    private val app = context.applicationContext
    private val request = UUID.randomUUID().toString()
    private val finished = CountDownLatch(1)
    private val collector = PdfHostCollector(request, Process.myPid(), Process.myUid())
    private val failure = AtomicReference<String?>()
    private val ready = AtomicReference<Boolean>(false)
    private val pipes = ParcelFileDescriptor.createPipe()
    private val reply = createReply()
    private val connection = createConnection()

    fun parse(): String {
        var bound = false
        try {
            bound = app.bindService(Intent(app, PdfDecodeService::class.java), connection, Context.BIND_AUTO_CREATE)
            require(bound) { "PDF 解析服务不可用" }
            if (!finished.await(30, TimeUnit.SECONDS)) throw IllegalArgumentException("PDF 解析耗时过长，请转为文本后导入")
            failure.get()?.let { throw IllegalArgumentException(it) }
            require(ready.get()) { "PDF 没有可读文字；扫描图片须先识别文字" }
            return collector.content().also { require(it.isNotBlank()) { "PDF 没有可读文字；扫描图片须先识别文字" } }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            throw java.util.concurrent.CancellationException("PDF 读取已取消")
        } finally {
            closePipes()
            if (bound) app.unbindService(connection)
        }
    }

    private fun createReply() = Messenger(Handler(Looper.getMainLooper()) { message ->
            if (message.data.getString("request") != request || ready.get() || failure.get() != null) return@Handler true
            collector.accept(PdfHostReply(message.what, message.data.getString("request"),
                message.data.getString("text"), message.data.getInt("sequence", -1),
                message.data.getLong("total", -1), message.data.getString("sha"),
                message.data.getInt("pid"), message.data.getInt("uid")))
            collector.failure?.let(failure::set)
            if (collector.ready) ready.set(true)
            if (collector.finished) finished.countDown()
            true
    })

    private fun createConnection() = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) = sendRequest(binder)
        override fun onServiceDisconnected(name: ComponentName) {
            if (!ready.get()) failure.compareAndSet(null, "PDF 解析中断，请转为文本后导入")
            finished.countDown()
        }
        override fun onBindingDied(name: ComponentName) = onServiceDisconnected(name)
        override fun onNullBinding(name: ComponentName) {
            failure.set("PDF 解析服务不可用"); finished.countDown()
        }
    }

    private fun sendRequest(binder: IBinder) {
        try {
            binder.linkToDeath({ failure.compareAndSet(null, "PDF 解析中断，请转为文本后导入"); finished.countDown() }, 0)
            Messenger(binder).send(Message.obtain(null, IsolatedPdfParser.REQUEST).apply {
                data = Bundle().apply { putString("request", request); putParcelable("input", pipes[0]) }
                replyTo = reply
            })
            pipes[0].close()
            writeInput()
        } catch (_: RemoteException) { failure.set("PDF 解析服务不可用"); finished.countDown() }
        catch (_: java.io.IOException) { failure.set("PDF 输入无法读取"); finished.countDown() }
    }

    private fun writeInput() = Thread({
        try { ParcelFileDescriptor.AutoCloseOutputStream(pipes[1]).use { it.write(bytes) } }
        catch (_: java.io.IOException) {
            if (!ready.get()) { failure.compareAndSet(null, "PDF 输入读取中断"); finished.countDown() }
        }
    }, "zhiyu-pdf-input").apply { isDaemon = true; start() }

    private fun closePipes() {
        pipes.forEach { try { it.close() } catch (_: java.io.IOException) { /* Already closed by transfer. */ } }
    }
}
