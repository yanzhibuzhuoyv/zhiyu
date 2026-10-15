package com.zhiyu.app.ui

import com.zhiyu.app.data.planar.PlanarJson
import com.zhiyu.app.domain.planar.*

internal object PlanarDraftCodec {
    fun encodeCheckpoint(draft:PlanarDraft,width:Int,height:Int):String {
        draft.validate(width,height,allowIncompleteParts=true)
        require(measure(draft)<=32*1024*1024){"草稿超过手机保存预算"}
        fun point(value:PlanarPoint)=listOf(value.x,value.y)
        fun rect(value:PlanarRect)=listOf(value.left,value.top,value.right,value.bottom)
        fun brush(values:List<PlanarBrushStroke>)=values.map {mapOf("points" to it.points.map(::point),"radius" to it.radius,"keep" to it.keep)}
        val a=draft.anchors;val v=draft.featureVisibility
        return PlanarJson.encode(mapOf("anchors" to mapOf("head" to point(a.head),"neck" to point(a.neck),
            "eyeLeft" to rect(a.eyeLeft),"eyeRight" to rect(a.eyeRight),"mouth" to rect(a.mouth)),
            "parts" to draft.parts.map {p->mapOf("id" to p.id,"name" to p.name,"role" to p.role,"z" to p.z,
                "polygon" to p.polygon.map(::point),"pivot" to point(p.pivot),"strength" to p.strength,
                "brush" to brush(p.brush),"mask" to p.baseMaskPng)},"subjectBrush" to brush(draft.subjectBrush),
            "subjectMask" to draft.subjectMask,"suggestionsConfirmed" to draft.suggestionsConfirmed,
            "featureVisibility" to mapOf("eyeLeft" to v.eyeLeft,"eyeRight" to v.eyeRight,"mouth" to v.mouth)))
            .also {require(it.length<=18*1024*1024){"草稿超过手机保存预算"}}
    }
    fun decodeCheckpoint(text:String,width:Int,height:Int):PlanarDraft = decode(text,width,height,true)
    private fun obj(value:Any?) = value as? Map<*,*> ?: error("角色标注数据不完整")
    private fun list(value:Any?) = value as? List<*> ?: error("角色标注数据不完整")
    private fun num(value:Any?) = (value as? Number)?.toDouble()?.also {require(it.isFinite())} ?: error("角色坐标无效")
    private fun point(value:Any?):PlanarPoint {val p=list(value);require(p.size==2);return PlanarPoint(num(p[0]),num(p[1]))}
    private fun rect(value:Any?):PlanarRect {val p=list(value);require(p.size==4);return PlanarRect(num(p[0]),num(p[1]),num(p[2]),num(p[3]))}
    private fun strokes(value:Any?)=(value as? List<*> ?: emptyList<Any>()).map { raw ->
        val p=obj(raw);PlanarBrushStroke(list(p["points"]).map(::point),num(p["radius"]),p["keep"] as? Boolean ?: error("笔迹无效")) }
    fun decode(text:String,width:Int,height:Int):PlanarDraft=decode(text,width,height,false)
    private fun decode(text:String,width:Int,height:Int,checkpoint:Boolean):PlanarDraft {
        if(checkpoint)require(text.length<=18*1024*1024){"草稿超过手机保存预算"}
        val d=obj(if(checkpoint)PlanarJson.parse(text,maxNodes=150_000,maxArray=4096)else PlanarJson.parse(text));val a=obj(d["anchors"])
        if(checkpoint)validateCheckpointFields(d,a)
        val anchors=PlanarAnchors(point(a["head"]),point(a["neck"]),rect(a["eyeLeft"]),rect(a["eyeRight"]),rect(a["mouth"]))
        val parts=(d["parts"] as? List<*> ?: emptyList<Any>()).map { raw -> val p=obj(raw)
            PlanarPart(p["id"] as String,p["name"] as String,p["role"] as String,num(p["z"]),
                (p["polygon"] as? List<*> ?: emptyList<Any>()).map(::point),point(p["pivot"]),num(p["strength"]),
                strokes(p["brush"]),p["mask"] as? String) }
        val visibility=if(d.containsKey("featureVisibility")) obj(d["featureVisibility"]) else null
        if(visibility!=null)require(visibility.keys==setOf("eyeLeft","eyeRight","mouth")){"眼口动作选项无效"}
        fun visible(key:String)=if(visibility==null)true else visibility[key] as? Boolean ?: error("眼口动作选项无效")
        val features=PlanarFeatureVisibility(visible("eyeLeft"),visible("eyeRight"),visible("mouth"))
        val confirmed=if(checkpoint)d["suggestionsConfirmed"] as? Boolean ?:error("草稿确认状态无效")else true
        return PlanarDraft(anchors,parts,strokes(d["subjectBrush"]),confirmed,d["subjectMask"] as? String,features)
            .also {it.validate(width,height,allowIncompleteParts=checkpoint);require(measure(it)<=32*1024*1024){"草稿超过手机保存预算"}}
    }
    private fun validateCheckpointFields(d:Map<*,*>,anchors:Map<*,*>) {
        require(d.keys==setOf("anchors","parts","subjectBrush","subjectMask","suggestionsConfirmed","featureVisibility")&&
            anchors.keys==setOf("head","neck","eyeLeft","eyeRight","mouth")){"草稿格式不完整"}
        require(d["subjectMask"]==null||d["subjectMask"] is String){"主体遮罩格式无效"}
        fun brush(value:Any?) {list(value).forEach {raw->val stroke=obj(raw)
            require(stroke.keys==setOf("points","radius","keep")&&stroke["keep"] is Boolean){"笔迹格式无效"}}}
        brush(d["subjectBrush"])
        val parts=list(d["parts"]);require(parts.size<=PlanarDraft.MAX_PARTS){"最多支持60个附件"}
        parts.forEach {raw->val p=obj(raw)
            require(p.keys==setOf("id","name","role","z","polygon","pivot","strength","brush","mask")&&
                (p["mask"]==null||p["mask"] is String)){"附件格式无效"};brush(p["brush"])}
    }
    fun measure(d:PlanarDraft):Int {
        val points=d.parts.sumOf {it.polygon.size+it.brush.sumOf {s->s.points.size}}+d.subjectBrush.sumOf {it.points.size}
        val text=(d.subjectMask?.length?.toLong() ?:0L)+d.parts.sumOf {(it.baseMaskPng?.length?.toLong() ?:0L)+it.name.length}
        return (4096L+points*32L+text*2L+d.parts.size*256L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }
}
