package com.zhiyu.app.data.remotescene

import com.zhiyu.app.domain.*
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class RemoteSceneFramingTest {
    @Test fun sameSourceAndTrajectoryWithNewFramingRevisionCannotReuseOldRequestOrApproval() {
        val png=byteArrayOf(137.toByte(),80,78,71,13,10,26,10,1)
        val chat="a".repeat(32)
        val source=OcAssetRef("b".repeat(32),ChatPhotoRules.sha(png),3)
        val policy=OcFramingPolicy(source,OcFraming.BUST,true,1,3)
        val profile=VisualProfile("import","OC","unspecified","long",3,source.id,null,GenerationQuota(false,0,0,2))
        fun binding(p:OcFramingPolicy,key:String="synthetic-image-key")=RemoteSceneBinding(chat,profile,null,source,key,
            Image2Protocol.DEFAULT_MODEL,SavedConnection("local://phone","deepseek","synthetic-chat-key","deepseek-v4-flash"),
            CompanionBinding("deepseek","deepseek-v4-flash"),png,framingPolicy=p)
        val scene=LocalDailyRules.scene(chat,3,Instant.parse("2026-10-15T06:00:00Z"))
        val target=LocalScenePrefetchTarget(scene,1,scene.slots[1],false)
        val first=binding(policy);val changed=binding(policy.copy(revision=2,allowExpansion=false))
        assertNotEquals(RemoteSceneRules.requestId(first,target,0),RemoteSceneRules.requestId(changed,target,0))
        assertNotEquals(RemoteSceneRules.bindingSha(first),RemoteSceneRules.bindingSha(changed))
        assertEquals(RemoteSceneRules.requestId(first,target,0),RemoteSceneRules.requestId(binding(policy,"changed-key"),target,0))
    }
}
