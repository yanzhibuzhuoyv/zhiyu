package com.zhiyu.app.data.planar

/** Old packages stay editable; only verified fixed-background packages are used for calls. */
internal object PlanarCallReadiness {
    fun fixedBackground(document:PlanarDocument):Boolean {
        val definition=try {PlanarJson.parse(document.definitionJson) as? Map<*,*>} catch(_:IllegalArgumentException){return false}
        val metadata=definition?.get("metadata") as? Map<*,*> ?:return false
        if(metadata["generator"]!="android-fixed-scene-v2"||metadata["backgroundRetained"]!=true||
            metadata["backgroundFixed"]!=true||metadata["sourceAlphaPreserved"]!=true||
            metadata["subjectMaskMeaning"]!="motion_constraint"||metadata["suggestionsConfirmed"]!=true)return false
        val base=document.layers.filter {it.id=="base"&&it.role=="base"}.singleOrNull() ?:return false
        return base.mesh.deltas().values.all {channel->channel.all {it==0f}}
    }
}
