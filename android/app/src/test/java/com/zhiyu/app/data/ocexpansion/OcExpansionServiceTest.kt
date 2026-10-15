package com.zhiyu.app.data.ocexpansion

import com.zhiyu.app.data.StoredImageJob
import com.zhiyu.app.data.remotescene.*
import com.zhiyu.app.domain.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class OcExpansionServiceTest {
    private val chat = "a".repeat(32)
    private val pixels = byteArrayOf(137.toByte(),80,78,71,13,10,26,10,1)
    private fun binding() = OcExpansionBinding(chat,
        OcFramingPolicy(OcAssetRef("b".repeat(32),ChatPhotoRules.sha(pixels),3),OcFraming.BUST,true,1,3),
        SavedConnection("local://phone","deepseek","synthetic-chat-key","deepseek-v4-flash"),
        CompanionBinding("deepseek","deepseek-v4-flash"),"synthetic-image-key",Image2Protocol.DEFAULT_MODEL,pixels)
    private inner class Fake : OcExpansionEnvironment {
        var current = true; var capacity = true; var frozen = binding(); var imageCalls = 0; var accepts = 0
        var reviews = 0; var status = LifePhotoReviewStatus.APPROVED; var failure:Exception? = null
        var reviewSha: String? = null; var afterPrompt:()->Unit = {}; var afterImage:()->Unit = {}
        val jobs = linkedMapOf<String,StoredImageJob>(); val saved = linkedMapOf<String,RemoteSceneCandidate>()
        val images = linkedMapOf<String,ByteArray>()
        override suspend fun freeze(chat:String) = frozen
        override suspend fun unchanged(binding:OcExpansionBinding) = current
        override fun hasCapacity() = capacity
        override fun job(id:String) = jobs[id]
        override fun reserve(job:LocalGenerationJob) { check(job.requestId !in jobs); jobs[job.requestId] = StoredImageJob(job,null) }
        override fun finish(id:String,status:String) { val old=jobs.getValue(id);jobs[id]=old.copy(job=old.job.copy(state=status)) }
        override fun candidate(id:String) = saved[id]
        override fun save(candidate:RemoteSceneCandidate,bytes:ByteArray) { saved[candidate.requestId]=candidate;images[candidate.requestId]=bytes.copyOf() }
        override fun bytes(candidate:RemoteSceneCandidate) = images.getValue(candidate.requestId).copyOf()
        override suspend fun prompt(binding:OcExpansionBinding):String { afterPrompt();return "Extend below the original, preserving its pose, visible wings, weapon, and background." }
        override suspend fun generate(binding:OcExpansionBinding,prompt:String):AgnesGeneratedImage { imageCalls++;failure?.let {throw it};afterImage();return AgnesGeneratedImage(pixels,"image/png") }
        override suspend fun review(binding:OcExpansionBinding,prompt:String,image:AgnesGeneratedImage):LifePhotoReview {
            reviews++;return LifePhotoReview(status,"pixel review",reviewSha?:ChatPhotoRules.sha(image.bytes))
        }
        override suspend fun accept(binding:OcExpansionBinding,candidate:RemoteSceneCandidate,bytes:ByteArray):Boolean {
            if(!current)return false;accepts++;return true
        }
    }
    @Test fun approvedPixelsStayPrivateUntilExplicitConfirmation() = runTest {
        val fake=Fake();val service=OcExpansionService(fake);val candidate=checkNotNull(service.generate(chat))
        assertEquals(1,fake.imageCalls);assertEquals(1,fake.reviews);assertEquals(0,fake.accepts)
        assertEquals(LifePhotoReviewStatus.APPROVED,candidate.status)
        assertTrue(service.confirm(chat,candidate.requestId,candidate.imageSha));assertEquals(1,fake.accepts)
        assertArrayEquals(pixels,fake.frozen.originalBytes)
    }
    @Test fun unknownNeverRepaysAfterRestartOrCredentialChange() = runTest {
        val fake=Fake().apply {failure=ApiFailure(504,"private failure","image_request_unknown")}
        OcExpansionService(fake).generate(chat);assertEquals("unknown",fake.jobs.values.single().job.state)
        val old=fake.frozen;fake.frozen=OcExpansionBinding(chat,old.policy,old.connection,old.companion,"changed-key","gpt-image-2",pixels)
        fake.failure=null;OcExpansionService(fake).generate(chat);assertEquals(1,fake.imageCalls)
    }
    @Test fun cancellationMarksUnknownWithoutReissuingImage() = runTest {
        val fake=Fake().apply {failure=CancellationException()}
        try {OcExpansionService(fake).generate(chat);fail()}catch(_:CancellationException){}
        assertEquals("unknown",fake.jobs.values.single().job.state)
        fake.failure=null;OcExpansionService(fake).generate(chat);assertEquals(1,fake.imageCalls)
    }
    @Test fun fullCapacityAndOptOutReserveNothing() = runTest {
        val fake=Fake().apply {capacity=false};assertNull(OcExpansionService(fake).generate(chat));assertTrue(fake.jobs.isEmpty())
        val old=fake.frozen;fake.capacity=true;fake.frozen=OcExpansionBinding(chat,old.policy.copy(allowExpansion=false),old.connection,old.companion,old.imageKey,old.imageModel,pixels)
        assertNull(OcExpansionService(fake).generate(chat));assertTrue(fake.jobs.isEmpty())
    }
    @Test fun staleAfterPromptNeverCallsImageAndAfterImageKeepsCandidate() = runTest {
        val a=Fake();a.afterPrompt={a.current=false};assertNull(OcExpansionService(a).generate(chat));assertEquals(0,a.imageCalls)
        assertEquals("stale",a.jobs.values.single().job.state)
        val b=Fake();b.afterImage={b.current=false};val candidate=OcExpansionService(b).generate(chat)
        assertNotNull(candidate);assertEquals(0,b.reviews);assertEquals(0,b.accepts);assertEquals("stale",b.jobs.values.single().job.state)
    }
    @Test fun unavailableOrWrongShaNeverConfirms() = runTest {
        listOf(LifePhotoReviewStatus.UNAVAILABLE,LifePhotoReviewStatus.REJECTED).forEach { status ->
            val fake=Fake().apply {this.status=status};val service=OcExpansionService(fake);val c=checkNotNull(service.generate(chat))
            try {service.confirm(chat,c.requestId,c.imageSha);fail()}catch(_:IllegalArgumentException){}
            assertEquals(0,fake.accepts)
        }
        val fake=Fake().apply {reviewSha="f".repeat(64)};val c=checkNotNull(OcExpansionService(fake).generate(chat))
        assertEquals(LifePhotoReviewStatus.UNKNOWN,c.status)
    }
    @Test fun changedKeyRequiresRecheckingSameStoredPixelsBeforeConfirmation() = runTest {
        val fake=Fake();val service=OcExpansionService(fake);val c=checkNotNull(service.generate(chat));val old=fake.frozen
        fake.frozen=OcExpansionBinding(chat,old.policy,old.connection.copy(apiKey="new-chat-key"),old.companion,old.imageKey,old.imageModel,pixels)
        try {service.confirm(chat,c.requestId,c.imageSha);fail()}catch(_:IllegalArgumentException){}
        val reviewed=service.recheck(chat,c.requestId)
        assertEquals(c.bindingSha,reviewed.bindingSha);assertEquals(c.imageSha,reviewed.imageSha)
        assertEquals(1,fake.imageCalls);assertEquals(2,fake.reviews)
        assertTrue(service.confirm(chat,reviewed.requestId,reviewed.imageSha))
    }
    @Test fun changedOriginalPolicyOrCandidatePixelsNeverAdopts() = runTest {
        val fake=Fake();val service=OcExpansionService(fake);val c=checkNotNull(service.generate(chat))
        fake.images[c.requestId]=pixels+byteArrayOf(2)
        try {service.confirm(chat,c.requestId,c.imageSha);fail()}catch(_:IllegalArgumentException){}
        assertEquals(0,fake.accepts)
    }
    @Test fun opportunityIdExcludesKeysButApprovalBindingIncludesThem() {
        val a=binding();val b=OcExpansionBinding(chat,a.policy,a.connection,a.companion,"changed-key","gpt-image-2",pixels)
        assertEquals(OcExpansionRules.requestId(a),OcExpansionRules.requestId(b))
        assertNotEquals(OcExpansionRules.bindingSha(a),OcExpansionRules.bindingSha(b))
    }
    @Test fun orphanPixelsWithoutLedgerNeverReserveOrRepay() = runTest {
        val fake=Fake();val service=OcExpansionService(fake)
        service.generate(chat);fake.jobs.clear() // Simulate a missing ledger, never delete a real user's records.
        service.generate(chat)
        assertEquals(1,fake.imageCalls);assertTrue(fake.jobs.isEmpty());assertEquals(1,fake.saved.size)
    }
}
