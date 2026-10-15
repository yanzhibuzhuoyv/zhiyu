package com.zhiyu.app.data.ocexpansion

import com.zhiyu.app.data.*
import com.zhiyu.app.data.remotescene.LifePhotoReviewStatus
import com.zhiyu.app.domain.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class OcExpansionPromptClientTest {
    private val png=byteArrayOf(137.toByte(),80,78,71,13,10,26,10,1)
    private class Store: CredentialStore {
        var saved=SavedConnection("local://phone","deepseek","synthetic-chat-key","deepseek-v4-flash")
        var companion=CompanionBinding("deepseek","deepseek-v4-flash")
        override fun read()=saved
        override fun readBinding()=companion
        override fun write(connection:SavedConnection){saved=connection}
        override fun writeBinding(binding:CompanionBinding){companion=binding}
        override fun clear()=Unit
    }
    private class Provider(var answer:String):DirectProviderClient() {
        var messages=emptyList<DirectChatMessage>();var calls=0;var after:()->Unit={}
        override suspend fun complete(providerId:String,key:String,model:String,messages:List<DirectChatMessage>,maxTokens:Int):String {
            assertEquals("synthetic-chat-key",key);this.messages=messages;calls++;after();return answer
        }
    }
    private fun binding(store:Store)=OcExpansionBinding("a".repeat(32),
        OcFramingPolicy(OcAssetRef("b".repeat(32),ChatPhotoRules.sha(png),3),OcFraming.BUST,true,1,3),
        store.saved,store.companion,"synthetic-image-key",Image2Protocol.DEFAULT_MODEL,png)
    private fun client(store:Store,provider:Provider)=OcExpansionPromptClient(store,provider,normalize={
        ChatPhotoPayload(ChatPhotoRules.sha(it),ChatPhotoRules.sha(it),"image/png",it)})
    private val approved="""{"approved":true,"reason":"synthetic pixels","checks":{"identity":true,"counts":true,"props":true,"pose":true,"background":true,"original":true,"full_body":true}}"""
    @Test fun promptUsesActualBoundChatKeyAndProtectsOriginalPoseBackgroundAndUnusualAnatomy()=runTest {
        val store=Store();val provider=Provider("""{"prompt":"Extend below the original without changing visible wings or the held weapon."}""")
        val result=client(store,provider).create(binding(store))
        assertTrue(result.contains("visible"));val rules=provider.messages.first().content
        assertTrue(rules.contains("background"));assertTrue(rules.contains("pose"));assertTrue(rules.contains("wings"))
        assertFalse(provider.messages.any {it.content.contains("synthetic-chat-key")||it.content.contains("synthetic-image-key")})
    }
    @Test fun reviewComparesActualOriginalAndCandidatePixelsAndNeverApprovesFromMetadataAlone()=runTest {
        val store=Store();val provider=Provider(approved);val actual=png+byteArrayOf(2)
        val result=client(store,provider).review(binding(store),"Extend",AgnesGeneratedImage(actual,"image/png"))
        assertEquals(LifePhotoReviewStatus.APPROVED,result.status)
        val photos=provider.messages.flatMap {it.photos};assertEquals(2,photos.size)
        assertArrayEquals(png,photos.first().bytes);assertArrayEquals(actual,photos.last().bytes)
        assertFalse(result.identityVerified)
    }
    @Test fun anyFailedProtectionOrMissingReviewCheckNeverApproves()=runTest {
        val store=Store();val provider=Provider(approved.replace("\"pose\":true","\"pose\":false"))
        assertEquals(LifePhotoReviewStatus.REJECTED,client(store,provider).review(binding(store),"Extend",AgnesGeneratedImage(png,"image/png")).status)
        provider.answer=approved.replace(",\"background\":true","")
        assertEquals(LifePhotoReviewStatus.UNKNOWN,client(store,provider).review(binding(store),"Extend",AgnesGeneratedImage(png,"image/png")).status)
    }
    @Test fun noNativeVisionLeavesCandidatePrivateAndDoesNotSilentlyChooseAnotherProvider()=runTest {
        val store=Store().apply {saved=saved.copy(model="deepseek-v4-pro");companion=companion.copy(model="deepseek-v4-pro")}
        val provider=Provider(approved)
        assertEquals(LifePhotoReviewStatus.UNAVAILABLE,client(store,provider).review(binding(store),"Extend",AgnesGeneratedImage(png,"image/png")).status)
        assertEquals(0,provider.calls)
    }
    @Test fun keyChangedDuringReviewInvalidatesApprovalWithoutASecondCall()=runTest {
        val store=Store();val provider=Provider(approved).apply {after={store.saved=store.saved.copy(apiKey="new-key")}}
        assertEquals(LifePhotoReviewStatus.UNKNOWN,client(store,provider).review(binding(store),"Extend",AgnesGeneratedImage(png,"image/png")).status)
        assertEquals(1,provider.calls)
    }
    @Test fun reflectedCredentialsCannotEnterSavedPrompts()=runTest {
        val store=Store();val provider=Provider("""{"prompt":"synthetic-image-key"}""")
        try {client(store,provider).create(binding(store));fail()}catch(_:ApiFailure){}
    }
}
