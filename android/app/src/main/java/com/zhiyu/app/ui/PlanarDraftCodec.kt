package com.zhiyu.app.ui

import com.zhiyu.app.data.planar.PlanarJson
import com.zhiyu.app.domain.planar.*

internal object PlanarDraftCodec {
    private fun obj(value:Any?) = value as? Map<*,*> ?: error("角色标注数据不完整")
    private fun list(value:Any?) = value as? List<*> ?: error("角色标注数据不完整")
    private fun num(value:Any?) = (value as? Number)?.toDouble()?.also {require(it.isFinite())} ?: error("角色坐标无效")
    private fun point(value:Any?):PlanarPoint {val p=list(value);require(p.size==2);return PlanarPoint(num(p[0]),num(p[1]))}
    private fun rect(value:Any?):PlanarRect {val p=list(value);require(p.size==4);return PlanarRect(num(p[0]),num(p[1]),num(p[2]),num(p[3]))}
    private fun strokes(value:Any?)=(value as? List<*> ?: emptyList<Any>()).map { raw ->
        val p=obj(raw);PlanarBrushStroke(list(p["points"]).map(::point),num(p["radius"]),p["keep"] as? Boolean ?: error("笔迹无效")) }
    fun decode(text:String,width:Int,height:Int):PlanarDraft {
        val d=obj(PlanarJson.parse(text));val a=obj(d["anchors"])
        val anchors=PlanarAnchors(point(a["head"]),point(a["neck"]),rect(a["eyeLeft"]),rect(a["eyeRight"]),rect(a["mouth"]))
        val parts=(d["parts"] as? List<*> ?: emptyList<Any>()).map { raw -> val p=obj(raw)
            PlanarPart(p["id"] as String,p["name"] as String,p["role"] as String,num(p["z"]),
                (p["polygon"] as? List<*> ?: emptyList<Any>()).map(::point),point(p["pivot"]),num(p["strength"]),
                strokes(p["brush"]),p["mask"] as? String) }
        return PlanarDraft(anchors,parts,strokes(d["subjectBrush"]),true,d["subjectMask"] as? String).also {it.validate(width,height)}
    }
    fun measure(d:PlanarDraft):Int {
        val points=d.parts.sumOf {it.polygon.size+it.brush.sumOf {s->s.points.size}}+d.subjectBrush.sumOf {it.points.size}
        val text=(d.subjectMask?.length?.toLong() ?:0L)+d.parts.sumOf {(it.baseMaskPng?.length?.toLong() ?:0L)+it.name.length}
        return (4096L+points*32L+text*2L+d.parts.size*256L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }
}
