package com.zhiyu.app.data.planar

import com.zhiyu.app.domain.planar.*
import org.junit.Assert.*
import org.junit.Test
import rig2d.planar.PlanarMesh

class PlanarHeadMotionSuggestionTest {
    @Test fun proposedRegionKeepsEveryUserLandmarkAndMotionRestriction() {
        val stroke=PlanarBrushStroke(listOf(PlanarPoint(5.0,5.0)),3.0,false)
        val original=PlanarDraft.suggest(320,480).copy(subjectBrush=listOf(stroke))
        val part=PlanarHeadMotionSuggestion.create(original,320,480)
        assertEquals("face",part.role)
        assertEquals(original.anchors.head,part.pivot)
        assertEquals(24,part.polygon.size)
        assertTrue(part.polygon.all {it.x in 1.0..318.0&&it.y in 1.0..478.0})
        assertTrue(original.parts.isEmpty())
        assertSame(stroke,original.subjectBrush.single())
        assertFalse(PlanarHeadMotionSuggestion.available(original.copy(parts=listOf(part))))
    }

    @Test fun nearEdgeLandmarkRequiresCorrectionRatherThanMovingTheOriginalAnchor() {
        val draft=PlanarDraft.suggest(320,480)
        val invalid=draft.copy(anchors=draft.anchors.copy(head=PlanarPoint(0.0,50.0)))
        assertThrows(IllegalArgumentException::class.java) {PlanarHeadMotionSuggestion.create(invalid,320,480)}
        assertEquals(PlanarPoint(0.0,50.0),invalid.anchors.head)
    }

    @Test fun eyeAndMouthInheritTheFaceFieldWithoutLosingTheirOwnExpressions() {
        val contract=PlanarPackageReader(PlanarPackageFixtures.contract).contract
        val parent=PlanarLocalHeadMotion.create(PlanarMesh(contract,floatArrayOf(0f,0f,200f,0f,0f,200f,200f,200f),
            floatArrayOf(0f,0f,1f,0f,0f,1f,1f,1f),intArrayOf(0,1,2,1,3,2)))
        val parentBase=parent.baseVertices()
        val nodes=intArrayOf(40,41,49)
        val childBase=nodes.flatMap {listOf(parentBase[it*2],parentBase[it*2+1])}.toFloatArray()
        val child=PlanarMesh(contract,childBase,floatArrayOf(0f,0f,1f,0f,0f,1f),intArrayOf(0,1,2),
            mapOf("mouth" to FloatArray(6){.25f},"blinkLeft" to FloatArray(6){.1f}))
        val inherited=PlanarLocalHeadMotion.inherit(child,parent)
        for(channel in listOf("headX","headY","tilt","breath","sway")) {
            val field=parent.deltas().getValue(channel)
            val expected=nodes.flatMap {listOf(field[it*2],field[it*2+1])}.toFloatArray()
            assertArrayEquals(expected,inherited.deltas().getValue(channel),.00001f)
        }
        assertArrayEquals(child.deltas().getValue("mouth"),inherited.deltas().getValue("mouth"),0f)
        assertArrayEquals(child.deltas().getValue("blinkLeft"),inherited.deltas().getValue("blinkLeft"),0f)
    }

    @Test fun zeroAndHalfStrengthActuallyControlTheSuggestedField() {
        val contract=PlanarPackageReader(PlanarPackageFixtures.contract).contract
        val source=PlanarMesh(contract,floatArrayOf(0f,0f,200f,0f,0f,200f,200f,200f),
            floatArrayOf(0f,0f,1f,0f,0f,1f,1f,1f),intArrayOf(0,1,2,1,3,2))
        val zero=PlanarLocalHeadMotion.create(source,0.0)
        val half=PlanarLocalHeadMotion.create(source,.5)
        val full=PlanarLocalHeadMotion.create(source)
        assertTrue(zero.deltas().values.all {values->values.all {it==0f}})
        assertTrue(full.deltas().getValue("headX").any {it!=0f})
        full.deltas().forEach {(channel,values)->
            assertArrayEquals(FloatArray(values.size){values[it]*.5f},half.deltas().getValue(channel),.00001f)
        }
        assertThrows(IllegalArgumentException::class.java) {PlanarLocalHeadMotion.create(source,Double.NaN)}
    }
}
