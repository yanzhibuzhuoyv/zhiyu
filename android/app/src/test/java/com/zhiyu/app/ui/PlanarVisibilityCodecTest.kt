package com.zhiyu.app.ui

import com.zhiyu.app.domain.planar.PlanarFeatureVisibility
import org.junit.Assert.*
import org.junit.Test

class PlanarVisibilityCodecTest {
    private val base="""{"anchors":{"head":[32,16],"neck":[32,28],"eyeLeft":[16,12,24,18],"eyeRight":[40,12,48,18],"mouth":[28,22,36,26]},"parts":[]}"""
    private fun decode(extra:String)=PlanarDraftCodec.decode(base.dropLast(1)+extra+"}",64,64)
    @Test fun oldDefinitionsDefaultToVisibleAndExplicitHiddenFeaturesSurviveReopen() {
        assertEquals(PlanarFeatureVisibility(),decode("").featureVisibility)
        assertEquals(PlanarFeatureVisibility(false,true,false),decode(""", "featureVisibility":{"eyeLeft":false,"eyeRight":true,"mouth":false}""").featureVisibility)
    }
    @Test fun malformedVisibilityNeverSilentlyEnablesAnUnseenFeature() {
        listOf("null","[]","\"bad\"","{}","{\"eyeLeft\":\"false\",\"eyeRight\":true,\"mouth\":true}",
            "{\"eyeLeft\":false,\"eyeRight\":true,\"mouth\":true,\"other\":false}").forEach {raw->
            assertThrows(Exception::class.java){decode(",\"featureVisibility\":$raw")}
        }
    }
}
