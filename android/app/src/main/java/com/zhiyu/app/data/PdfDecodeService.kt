package com.zhiyu.app.data

import android.app.Service
import android.content.Intent
import android.os.*
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.zhiyu.app.domain.ConnectionRules
import java.io.ByteArrayOutputStream
import java.io.ByteArrayInputStream
import java.io.Writer
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

/** Isolated UID has no app files or network permissions. It is destroyed after the binding ends. */
class PdfDecodeService : Service() {
    private val busy = AtomicBoolean(false)
    private val messenger = Messenger(Handler(Looper.getMainLooper()) { message ->
        val target = message.replyTo
        val request = message.data.getString("request").orEmpty()
        @Suppress("DEPRECATION")
        val input = message.data.getParcelable("input") as? ParcelFileDescriptor
        if (target == null || request.length != 36 || input == null) {
            input?.close()
        } else if (message.what != IsolatedPdfParser.REQUEST || !busy.compareAndSet(false, true)) {
            input.close(); send(target, request, IsolatedPdfParser.FAILURE)
        } else Thread({ decode(target, request, input) }, "zhiyu-pdf-decoder").apply { isDaemon = true; start() }
        true
    })
    override fun onBind(intent: Intent): IBinder = messenger.binder
    override fun onDestroy() {
        super.onDestroy()
        if (Process.myUid() != applicationInfo.uid) Process.killProcess(Process.myPid())
    }
    private fun decode(target: Messenger, request: String, descriptor: ParcelFileDescriptor) {
        try {
            val raw = ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    require(output.size().toLong() + count <= ConnectionRules.MAX_FILE_BYTES)
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
            require(raw.size >= 5 && raw.copyOfRange(0, 5).toString(Charsets.US_ASCII) == "%PDF-")
            PDFBoxResourceLoader.init(this)
            // No filesystem scratch: exhaustion throws rather than expanding app-private storage.
            val output = ChunkWriter(target, request)
            PDDocument.load(ByteArrayInputStream(raw), MemoryUsageSetting.setupMainMemoryOnly(8 * 1024 * 1024L)).use { document ->
                require(!document.isEncrypted && document.numberOfPages in 1..500)
                output.use { PDFTextStripper().writeText(document, it) }
            }
            send(target, request, IsolatedPdfParser.COMPLETE, total = output.total, sequence = output.sequence, sha = output.checksum())
        } catch (_: Exception) { fail(target, request) }
        catch (_: OutOfMemoryError) { fail(target, request); Process.killProcess(Process.myPid()) }
        finally { busy.set(false) }
    }
    private class ChunkWriter(private val target: Messenger, private val request: String) : Writer() {
        var total = 0L
            private set
        var sequence = 0
            private set
        private val digest = MessageDigest.getInstance("SHA-256")
        private val chunk = StringBuilder(16384)
        override fun write(chars: CharArray, offset: Int, length: Int) {
            require(length >= 0 && offset >= 0 && offset.toLong() + length <= chars.size)
            total += length
            require(total <= IsolatedPdfParser.MAX_TEXT)
            for (index in offset until offset + length) {
                digest.update((chars[index].code ushr 8).toByte()); digest.update(chars[index].code.toByte())
            }
            var position = offset
            while (position < offset + length) {
                val count = minOf(16384 - chunk.length, offset + length - position)
                chunk.append(chars, position, count)
                position += count
                if (chunk.length == 16384) flush()
            }
        }
        override fun flush() { if (chunk.isNotEmpty()) {
            send(target, request, IsolatedPdfParser.TEXT, chunk.toString(), sequence = sequence)
            sequence++; chunk.setLength(0)
        } }
        override fun close() = flush()
        fun checksum(): String = digest.digest().joinToString("") { "%02x".format(it) }
    }
    companion object {
        private fun fail(target: Messenger, request: String) {
            try { send(target, request, IsolatedPdfParser.FAILURE) }
            catch (_: RemoteException) { /* A disconnected host will detect binder death or timeout. */ }
        }
        private fun send(target: Messenger, request: String, kind: Int, text: String? = null,
            sequence: Int = 0, total: Long = 0, sha: String = "") {
            target.send(Message.obtain(null, kind).apply { data = Bundle().apply {
                putString("request", request); putInt("pid", Process.myPid()); putInt("uid", Process.myUid())
                putInt("sequence", sequence); putLong("total", total); putString("sha", sha)
                text?.let { putString("text", it) }
            } })
        }
    }
}
