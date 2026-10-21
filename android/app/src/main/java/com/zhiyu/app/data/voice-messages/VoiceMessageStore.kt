package com.zhiyu.app.data.voicemessages

import android.content.Context
import android.util.AtomicFile
import com.zhiyu.app.data.LocalChatDatabase
import com.zhiyu.app.data.LocalVoiceAudio
import com.zhiyu.app.domain.LocalMemoryCodec
import com.zhiyu.app.domain.VoiceTuningSettings
import java.io.File
import java.util.UUID

enum class VoiceClipState { PENDING, READY, FAILED }

data class VoiceClipRecord(
    val id: String, val owner: String? = null, val chatId: String? = null,
    val messageId: String? = null, val sourceSha: String? = null,
    val durationMillis: Long = 0, val sampleRate: Int = 16000,
    val state: VoiceClipState = VoiceClipState.PENDING, val errorCode: String? = null,
    val pitchSemitones: Float = 0f, val volume: Float = 1f,
)

class VoiceMessageStore(context: Context, directoryName: String = "voice_messages",
    private val databaseName: String = "zhiyu_voice_v1.sqlite3", private val maxBytes: Long = 512L * 1024 * 1024) {
    private val app = context.applicationContext ?: context
    private val parent = app.filesDir.canonicalFile
    private val root = File(parent, directoryName)
    init {
        require(directoryName.matches(Regex("[A-Za-z0-9_-]{1,100}")))
        require(databaseName.matches(Regex("[A-Za-z0-9_-]{1,100}\\.sqlite3")))
        require(maxBytes in 8192..512L * 1024 * 1024)
        require(root.canonicalFile == root && root.parentFile == parent) { "语音目录无效" }
        check(root.isDirectory || root.mkdir()) { "语音目录暂时无法准备" }
    }
    fun recordDraft(samples: FloatArray, sampleRate: Int, transcript: String): VoiceClipRecord = synchronized(lock) {
        require(transcript.isNotBlank() && transcript.length <= 128000) { "语音转写无效" }
        val transcriptSha = sha(LocalMemoryCodec.bytes(transcript))
        val bytes = VoiceWavCodec.encode(samples, sampleRate, 60)
        val id = UUID.randomUUID().toString().replace("-", "")
        ledger { db ->
            capacity(bytes.size.toLong() + 16384)
            write(id, bytes)
            try { db.insert(id, "user", transcriptSha, VoiceClipState.READY,
                duration(samples.size, sampleRate), sampleRate, sha(bytes), bytes.size, sourceSha = transcriptSha) }
            catch (failure: Exception) { AtomicFile(file(id)).delete(); throw failure }
            db.entry(id)!!.record
        }
    }
    fun find(id: String): VoiceClipRecord? = synchronized(lock) { validId(id); ledger { it.entry(id)?.record } }
    fun read(id: String): LocalVoiceAudio = synchronized(lock) {
        validId(id)
        val row = ledger { it.entry(id) } ?: throw IllegalArgumentException("这条语音暂时不可用")
        require(row.record.state == VoiceClipState.READY && row.audioBytes in 46..VoiceWavCodec.MAX_BYTES)
        val source = file(id)
        require(source.isFile && source.length() == row.audioBytes.toLong()) { "语音文件不完整" }
        val bytes = source.readBytes()
        require(sha(bytes) == row.audioSha) { "语音文件校验不一致" }
        VoiceWavCodec.decode(bytes).also {
            require(it.sampleRate == row.record.sampleRate && duration(it.samples.size, it.sampleRate) == row.record.durationMillis)
        }
    }
    fun discardDraft(id: String): Unit = synchronized(lock) {
        validId(id)
        ledger { db ->
            val row = db.entry(id) ?: return@ledger
            if (row.kind != "user" || row.record.owner != null || referencedByChat(id)) return@ledger
            // Dropping metadata first can at worst leave an orphan, never a broken bound pointer.
            db.delete(id)
            AtomicFile(file(id)).delete()
        }
    }
    fun linkDraft(id: String, owner: String, chatId: String, messageId: String, sourceSha: String): VoiceClipRecord = synchronized(lock) {
        identity(id, owner, chatId, messageId, sourceSha)
        ledger { db ->
            val row = db.entry(id) ?: throw IllegalArgumentException("语音草稿已不可用")
            require(row.kind == "user" && row.record.state == VoiceClipState.READY && row.transcriptSha == sourceSha) { "语音与发送原话不一致" }
            if (row.record.owner != null) require(matches(row.record, owner, chatId, messageId, sourceSha)) { "语音已属于另一条消息" }
            else db.update(id, "owner" to owner, "chat_id" to chatId, "message_id" to messageId, "source_sha" to sourceSha)
            db.entry(id)!!.record
        }
    }
    internal fun prepareReply(id: String, owner: String, chatId: String, messageId: String,
        sourceSha: String, tuning: VoiceTuningSettings): VoiceClipRecord = synchronized(lock) {
        identity(id, owner, chatId, messageId, sourceSha)
        ledger { db ->
            db.entry(id)?.let {
                require(it.kind == "reply" && matches(it.record, owner, chatId, messageId, sourceSha)) { "语音来源不一致" }
                return@ledger it.record
            }
            check(db.pendingCount() < 16) { "语音准备队列已满，文字仍可阅读" }
            capacity(16384)
            db.insert(id, "reply", null, VoiceClipState.PENDING, owner = owner, chat = chatId,
                message = messageId, sourceSha = sourceSha, tuning = tuning)
            db.entry(id)!!.record
        }
    }
    internal fun claim(id: String, tuning: VoiceTuningSettings? = null): Boolean = synchronized(lock) {
        validId(id)
        ledger { db ->
            val row = db.entry(id) ?: return@ledger false
            if (row.kind != "reply" || row.record.state != VoiceClipState.PENDING || row.attempts != 0) return@ledger false
            if (tuning == null) db.update(id, "attempts" to 1)
            else db.update(id, "attempts" to 1, "pitch" to tuning.pitchSemitones, "volume" to tuning.volume)
            true
        }
    }
    internal fun completeReply(id: String, audio: LocalVoiceAudio, sourceCurrent: () -> Boolean): VoiceClipRecord = synchronized(lock) {
        validId(id)
        check(sourceCurrent()) { "语音来源已经改变" }
        val bytes = VoiceWavCodec.encode(audio.samples, audio.sampleRate, 180)
        ledger { db ->
            val row = db.entry(id) ?: throw IllegalStateException("语音来源已经改变")
            check(row.kind == "reply" && row.record.state == VoiceClipState.PENDING && row.attempts == 1)
            capacity(bytes.size.toLong() + 16384); write(id, bytes)
            try {
                check(sourceCurrent()) { "语音来源已经改变" }
                db.update(id, "state" to VoiceClipState.READY.name, "duration_ms" to duration(audio.samples.size, audio.sampleRate),
                    "sample_rate" to audio.sampleRate, "audio_sha" to sha(bytes), "audio_bytes" to bytes.size, "error_code" to null)
            } catch (failure: Exception) { AtomicFile(file(id)).delete(); throw failure }
            db.entry(id)!!.record
        }
    }
    internal fun fail(id: String, errorCode: String): Unit = synchronized(lock) {
        validId(id); require(errorCode.matches(Regex("[a-z_]{1,40}")))
        ledger { db -> db.entry(id)?.let { if (it.record.state == VoiceClipState.PENDING)
            db.update(id, "state" to VoiceClipState.FAILED.name, "error_code" to errorCode) } }
    }
    private fun referencedByChat(id: String): Boolean {
        if (!app.getDatabasePath("zhiyu_local_v1.sqlite3").isFile) return false
        return LocalChatDatabase(app).use { archive -> archive.readableDatabase.rawQuery(
            "SELECT 1 FROM messages WHERE origin=? UNION ALL SELECT 1 FROM outgoing_drafts WHERE voice_id=? LIMIT 1",
            arrayOf(VoiceMessageOrigins.user(id), id)).use { it.moveToFirst() } }
    }
    private fun file(id: String): File {
        validId(id)
        require(root.canonicalFile == root && root.parentFile == parent)
        return File(root, "$id.wav").also { target ->
            require(target.canonicalFile == target && listOf(".bak", ".new").all { suffix ->
                File(root, target.name + suffix).let { it.canonicalFile == it }
            }) { "语音文件路径无效" }
        }
    }
    private fun write(id: String, bytes: ByteArray) {
        val target = file(id)
        check(!target.exists() && !File(root, target.name + ".bak").exists() && !File(root, target.name + ".new").exists()) { "已有语音文件，暂不覆盖" }
        val atomic = AtomicFile(target)
        val output = atomic.startWrite()
        try { output.write(bytes); output.fd.sync(); atomic.finishWrite(output) }
        catch (failure: Exception) { atomic.failWrite(output); throw failure }
    }
    private fun capacity(extra: Long) {
        require(root.canonicalFile == root)
        val files = root.listFiles() ?: throw IllegalStateException("语音空间暂时无法检查")
        require(files.size <= 65536 && files.all { it.canonicalFile == it && it.isFile }) { "语音目录内容无效" }
        val ledgerFile = app.getDatabasePath(databaseName)
        val ledgerBytes = listOf("", "-journal", "-wal", "-shm").sumOf { suffix -> File(ledgerFile.path + suffix).length() }
        check(extra in 0..maxBytes && files.sumOf(File::length) + ledgerBytes <= maxBytes - extra) { "语音空间已满，已发送语音仍保留" }
    }
    private fun <T> ledger(block: (VoiceClipLedger) -> T): T = VoiceClipLedger(app, databaseName).use {
        it.readableDatabase; block(it)
    }
    private fun identity(id: String, owner: String, chat: String, message: String, sourceSha: String) {
        validId(id)
        require(owner.isNotBlank() && owner.length <= 256 && owner.none(Char::isISOControl))
        require(listOf(chat, message).all { it.matches(Regex("[A-Za-z0-9_-]{1,128}")) })
        require(sourceSha.matches(Regex("[a-f0-9]{64}")))
    }
    private fun matches(row: VoiceClipRecord, owner: String, chat: String, message: String, sha: String) =
        row.owner == owner && row.chatId == chat && row.messageId == message && row.sourceSha == sha
    private fun validId(id: String) { require(id.matches(Regex("[a-f0-9]{32}"))) { "语音标识无效" } }
    private fun sha(bytes: ByteArray) = LocalMemoryCodec.sha(bytes)
    private fun duration(samples: Int, rate: Int) = samples.toLong() * 1000 / rate
    companion object { private val lock = Any() }
}
