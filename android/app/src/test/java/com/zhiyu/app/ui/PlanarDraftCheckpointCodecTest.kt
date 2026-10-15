package com.zhiyu.app.ui

import com.zhiyu.app.domain.planar.*
import org.junit.Assert.*
import org.junit.Test

class PlanarDraftCheckpointCodecTest {
    @Test fun unfinishedAccessoryAndUnconfirmedSuggestionsSurviveCheckpoint() {
        val original=PlanarDraft.suggest(64,64).copy(parts=listOf(PlanarPart("wing","左翼","accessory",20.0,
            listOf(PlanarPoint(2.0,3.0)),PlanarPoint(4.0,5.0),.5)),
            subjectBrush=listOf(PlanarBrushStroke(listOf(PlanarPoint(6.0,7.0)),2.0,false)),
            featureVisibility=PlanarFeatureVisibility(false,true,false))
        val restored=PlanarDraftCodec.decodeCheckpoint(PlanarDraftCodec.encodeCheckpoint(original,64,64),64,64)
        assertFalse(restored.suggestionsConfirmed);assertEquals(original.parts.single().polygon,restored.parts.single().polygon)
        assertEquals(original.featureVisibility,restored.featureVisibility);assertFalse(restored.subjectBrush.single().keep)
        assertThrows(IllegalArgumentException::class.java){restored.validate(64,64)}
    }
    @Test fun invalidCoordinatesAreRejectedBeforeDurableEncoding() {
        val original=PlanarDraft.suggest(64,64)
        assertThrows(IllegalArgumentException::class.java){PlanarDraftCodec.encodeCheckpoint(original.copy(
            anchors=original.anchors.copy(head=PlanarPoint(Double.NaN,3.0))),64,64)}
    }
}
