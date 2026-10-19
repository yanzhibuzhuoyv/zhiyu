package com.zhiyu.app.data.planar

import org.junit.Assert.*
import org.junit.Test
import rig2d.planar.PlanarMesh
import kotlin.math.abs
import kotlin.math.floor

class PlanarMaskedHeadMotionTest {
    private val contract = PlanarPackageReader(PlanarPackageFixtures.contract).contract
    private fun mesh() = PlanarMesh(contract, floatArrayOf(0f,0f,128f,0f,0f,128f,128f,128f),
        floatArrayOf(0f,0f,1f,0f,0f,1f,1f,1f),intArrayOf(0,1,2,1,3,2))
    private fun cMask() = ByteArray(128*128) {index->
        val x=index%128;val y=index/128
        if(x<28 || y<28 || y>=100) 1 else 0
    }

    @Test fun hollowManualContoursKeepVisibleInteriorMotionWhileTheirBoundaryAndHoleStayFixed() {
        val mask=cMask()
        val head=PlanarMaskedHeadMotion.create(mesh(),128,128,mask,1.0)
        val base=head.baseVertices()
        val field=head.deltas().getValue("headX")
        assertTrue("A hollow centre must not make the selected left side static",base.indices.step(2).any {index->
            base[index] in 6f..22f && base[index+1] in 40f..84f && field[index]>.5f
        })
        for(index in base.indices step 2) {
            val x=base[index];val y=base[index+1]
            if(x==0f||y==0f||x==128f||y==128f||(x in 32f..124f&&y in 32f..96f)) {
                assertEquals(0f,field[index],0f)
                head.deltas().values.forEach {values->assertEquals(0f,values[index],0f);assertEquals(0f,values[index+1],0f)}
            }
        }
        // Every triangle affected by this field remains inside the real selected pixels, even
        // at the concave corners. Zero transparent vertices alone would not establish that.
        for(triangle in head.indices().toList().chunked(3)) {
            if(triangle.none {node->abs(field[node*2])>0f})continue
            for(a in 0..4)for(b in 0..(4-a)) {
                val weights=floatArrayOf(a/4f,b/4f,(4-a-b)/4f)
                val x=triangle.indices.sumOf {i->(base[triangle[i]*2]*weights[i]).toDouble()}
                val y=triangle.indices.sumOf {i->(base[triangle[i]*2+1]*weights[i]).toDouble()}
                assertEquals("A moving triangle must not cross the manual hole",1,
                    mask[floor(y).toInt().coerceIn(0,127)*128+floor(x).toInt().coerceIn(0,127)].toInt())
            }
        }
        assertTrue(base.size/2<=contract.maxVertices)
        assertTrue(head.indices().size/3<=contract.maxTriangles)
    }

    @Test fun strengthAndAnErasedActivityMaskAreRespectedWithoutReintroducingTheOldEllipse() {
        val mask=cMask()
        val full=PlanarMaskedHeadMotion.create(mesh(),128,128,mask,1.0)
        val half=PlanarMaskedHeadMotion.create(mesh(),128,128,mask,.5)
        val zero=PlanarMaskedHeadMotion.create(mesh(),128,128,mask,0.0)
        val erased=PlanarMaskedHeadMotion.create(mesh(),128,128,ByteArray(128*128),1.0)
        assertTrue(zero.deltas().values.all {v->v.all {it==0f}})
        assertTrue(erased.deltas().values.all {v->v.all {it==0f}})
        full.deltas().forEach {(channel,v)->
            assertArrayEquals(FloatArray(v.size){v[it]*.5f},half.deltas().getValue(channel),0f)
        }
        assertEquals(0,mask[64*128+64].toInt())
        assertThrows(IllegalArgumentException::class.java) {PlanarMaskedHeadMotion.create(mesh(),128,128,mask,Double.NaN)}
        assertThrows(IllegalArgumentException::class.java) {PlanarMaskedHeadMotion.create(mesh(),128,128,ByteArray(2),1.0)}
    }
}
