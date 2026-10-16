package com.zhiyu.app.data.voicemessages

import com.zhiyu.app.data.LocalMemoryOriginal
import com.zhiyu.app.data.LocalVoiceAudio
import com.zhiyu.app.data.LocalVoiceRules
import com.zhiyu.app.domain.VoiceTuningSettings
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** A derived audio task; the source body remains intact and never becomes generated memory. */
internal class VoiceReplyPreparation(
    private val store: VoiceMessageStore,
    private val source: () -> LocalMemoryOriginal?,
    private val settings: () -> Pair<Int, VoiceTuningSettings>,
    private val synthesize: suspend (String, Int, VoiceTuningSettings, () -> Boolean) -> LocalVoiceAudio,
) {
    suspend fun run(id: String, owner: String, chat: String, message: String, sha: String) {
        val original = source() ?: throw IllegalArgumentException("语音原话已不可用")
        require(original.owner == owner && original.conversationId == chat && original.messageId == message &&
            original.role == "assistant" && original.sha256 == sha && original.origin == VoiceMessageOrigins.reply(id)) { "语音来源不一致" }
        val (voice, tuning) = settings()
        LocalVoiceRules.voice(voice)
        val record = store.find(id) ?: throw IllegalStateException("语音准备状态不可用")
        require(record.owner == owner && record.chatId == chat && record.messageId == message && record.sourceSha == sha) { "语音准备来源不一致" }
        if (record.state != VoiceClipState.PENDING) return
        if (!store.claim(id, tuning)) { store.fail(id, "interrupted"); return }
        val context = currentCoroutineContext()
        val pieces = mutableListOf<FloatArray>()
        var rate = 0
        var total = 0L
        for (text in LocalVoiceRules.speechChunks(original.text)) {
            context.ensureActive()
            check(current(original)) { "语音来源已经改变" }
            val audio = synthesize(text, voice, tuning) { context[kotlinx.coroutines.Job]?.isActive != false }
            VoiceWavCodec.validate(audio.samples, audio.sampleRate, 180)
            if (rate == 0) rate = audio.sampleRate
            require(rate == audio.sampleRate) { "语音分段采样率不一致" }
            total += audio.samples.size
            require(total <= rate.toLong() * 180) { "这段语音超过三分钟，文字仍完整保留" }
            pieces.add(audio.samples)
        }
        context.ensureActive()
        val combined = FloatArray(total.toInt())
        var offset = 0
        pieces.forEach { it.copyInto(combined, offset); offset += it.size }
        store.completeReply(id, LocalVoiceAudio(combined, rate)) { context.ensureActive(); current(original) }
    }
    private fun current(original: LocalMemoryOriginal): Boolean = source()?.let {
        it.owner == original.owner && it.conversationId == original.conversationId && it.messageId == original.messageId &&
            it.role == original.role && it.sha256 == original.sha256 && it.revision == original.revision && it.origin == original.origin
    } == true
}
