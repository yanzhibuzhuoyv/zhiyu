package com.zhiyu.app.domain

import org.junit.Assert.*
import org.junit.Test

class VoiceInputInterpretationTest {
    @Test fun archivedVoiceHasAsrProvenanceButDoesNotPretendTheProviderReceivedAudio() {
        val result = VoiceInputInterpretation.render(VoiceInputRuntime(VoiceInputSource.ASR_TRANSCRIPT, true))
        assertTrue(result.contains("input_modality=asr_transcript"))
        assertTrue(result.contains("original_audio_available=true"))
        assertTrue(result.contains("audio_supplied_to_model=false"))
        assertTrue(result.contains("不能声称听出了"))
    }

    @Test fun callTranscriptDoesNotInventAnArchivedAudioAttachment() {
        val result = VoiceInputInterpretation.render(VoiceInputRuntime(VoiceInputSource.ASR_TRANSCRIPT))
        assertTrue(result.contains("original_audio_available=false"))
        assertTrue(result.contains("audio_supplied_to_model=false"))
    }

    @Test fun typedTextIsNeverGrantedAsrCorrectionStatus() {
        val result = VoiceInputInterpretation.render(VoiceInputRuntime())
        assertTrue(result.contains("input_modality=text"))
        assertFalse(result.contains("高置信的同音"))
        assertTrue(result.contains("audio_supplied_to_model=false"))
    }

    @Test fun homophoneGuidanceProtectsNamesNumbersNegationAndTheUnchangedOriginal() {
        val result = VoiceInputInterpretation.render(VoiceInputRuntime(VoiceInputSource.ASR_TRANSCRIPT, true))
        listOf("高置信的同音", "人名", "地名", "数值", "日期", "否定", "意愿", "不改写原文", "不把纠错当成话题", "简短确认").forEach {
            assertTrue("Missing interpretation boundary: $it", result.contains(it))
        }
    }

    @Test fun typedProvenanceCannotFalselyClaimAnOriginalRecording() {
        try { VoiceInputRuntime(VoiceInputSource.TEXT, true); fail("Typed input cannot acquire recorded-audio provenance") }
        catch (_: IllegalArgumentException) { }
    }
}
