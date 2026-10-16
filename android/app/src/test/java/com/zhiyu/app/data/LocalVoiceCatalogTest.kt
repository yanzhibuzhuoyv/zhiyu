package com.zhiyu.app.data

import org.junit.Assert.*
import org.junit.Test

class LocalVoiceCatalogTest {
    @Test fun allActualModelSpeakersBelongToExactlyOneMetadataGroup() {
        val groups = VoiceGender.entries.map { LocalVoiceCatalog.ids(it) }
        assertEquals((0 until 174).toList(), groups.flatten().sorted())
        assertEquals(174, groups.flatten().distinct().size)
        assertEquals(138, LocalVoiceCatalog.ids(VoiceGender.FEMALE).size)
        assertEquals(36, LocalVoiceCatalog.ids(VoiceGender.MALE).size)
        assertTrue(LocalVoiceCatalog.ids(VoiceGender.UNKNOWN).isEmpty())
    }

    @Test fun actualSpeakerMetadataIsUsedInsteadOfIdParity() {
        assertEquals(VoiceGender.FEMALE, LocalVoiceCatalog.gender(0)) // SSB0005
        assertEquals(VoiceGender.FEMALE, LocalVoiceCatalog.gender(1)) // SSB0009
        assertEquals(VoiceGender.MALE, LocalVoiceCatalog.gender(15)) // SSB0139
        assertEquals(VoiceGender.FEMALE, LocalVoiceCatalog.gender(16)) // SSB0145
        assertEquals(VoiceGender.FEMALE, LocalVoiceCatalog.gender(33)) // SSB0323
    }

    @Test fun unknownSpeakerIdIsRejectedBeforeAnyModelSelection() {
        listOf(-1, 174, Int.MAX_VALUE).forEach { id ->
            assertThrows(IllegalArgumentException::class.java) { LocalVoiceCatalog.gender(id) }
        }
    }
}
