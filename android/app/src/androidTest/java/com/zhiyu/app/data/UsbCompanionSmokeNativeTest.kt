package com.zhiyu.app.data

import android.os.Bundle
import androidx.test.platform.app.InstrumentationRegistry
import com.zhiyu.app.domain.*
import com.zhiyu.app.notifications.ProactiveForeground
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.time.ZoneId
import java.util.UUID
import java.util.Timer
import java.util.TimerTask

/** Manual USB-only actual-chat smoke. Explicit arguments distinguish a free clock check from a paid photo request. */
class UsbCompanionSmokeNativeTest {
    @Test fun sendOneExplicitlyRequestedDeviceSmokeMessage(): Unit = runBlocking {
        val runner = InstrumentationRegistry.getInstrumentation()
        val action = InstrumentationRegistry.getArguments().getString("zhiyu_usb_action")
        assumeTrue(action in setOf("clock", "selfie", "photo_capability", "inspect_photo"))
        val app = runner.targetContext
        val stage = java.util.concurrent.atomic.AtomicReference("setup")
        val watchdog = Timer("USB-smoke-stack-watchdog", true).apply {
            schedule(object : TimerTask() { override fun run() {
                val dump = Thread.getAllStackTraces().entries.joinToString("\n\n") { (thread, stack) ->
                    "${thread.name} ${thread.state}\n" + stack.joinToString("\n") { "  $it" }
                }
                app.filesDir.resolve("usb_companion_stack.txt").writeText("stage=${stage.get()}\n$dump")
                runner.sendStatus(2, Bundle().apply { putString("zhiyu_usb_stage", stage.get()) })
            } }, 30_000, 30_000)
        }
        val oldForeground = ProactiveForeground.isVisible()
        ProactiveForeground.setVisible(true)
        try {
            val provider = if (action == "clock") DirectProviderClient { _, _, _ ->
                throw AssertionError("The actual-device clock question must not call a model")
            } else DirectProviderClient()
            val repo = LocalChatRepository(app, appCredentialStore(app), provider)
            stage.set("conversations")
            val chat = repo.conversations().single()
            if (action == "inspect_photo") {
                stage.set("inspect_photo")
                val visual = LocalVisualRepository(app).profile(chat.id)
                val scene = LocalDailyRepository(app).use { it.scene(chat.id, visual.visualVersion) }
                val environment = com.zhiyu.app.data.remotescene.PhoneRemoteSceneEnvironment(app)
                val creator = OcCreatorStore(app).read(chat.id)
                val items = org.json.JSONArray()
                com.zhiyu.app.data.selfie.LocalSelfieBank(app).targets(chat.id, visual, scene).forEach { target ->
                    val frozen = environment.freeze(chat.id, target, visual, creator)
                    val item = JSONObject().put("lane", target.selfie?.lane).put("generation", target.selfie?.generation)
                        .put("alreadyPrepared", environment.alreadyPrepared(chat.id, target, visual)).put("bindingAvailable", frozen != null)
                    if (frozen != null) {
                        item.put("unchanged", environment.unchanged(frozen, target))
                        (0..1).forEach { attempt ->
                            val id = com.zhiyu.app.data.remotescene.RemoteSceneRules.requestId(frozen, target, attempt)
                            item.put("job$attempt", environment.job(id)?.job?.state)
                            environment.candidate(id)?.let { item.put("candidate$attempt", it.status.name).put("reason$attempt", it.reason) }
                        }
                    }
                    items.put(item)
                }
                runner.sendStatus(2, Bundle().apply { putString("zhiyu_usb_photo_inventory", items.toString()) })
                return@runBlocking
            }
            stage.set("send")
            val input = when (action) { "selfie" -> "给我拍张自拍"; "photo_capability" -> "你现在可以给我发照片吗？"; else -> "现在几点" }
            val result = withTimeout(420_000) {
                repo.sendQueuedMessage(chat.id, input, UUID.randomUUID().toString().replace("-", ""),
                    onSubmitted = { stage.set("user_committed") })
            }
            stage.set("completed")
            val report = JSONObject().put("action", action).put("photoStatus", repo.lastPhotoStatus)
                .put("userSaved", result.first.content == input).put("answerSaved", result.second.id.isNotBlank())
                .put("attachments", result.second.mediaAttachments.size).put("answerChars", result.second.content.length)
                .put("currentTime", PhoneClock.now().atZone(ZoneId.systemDefault()).toString())
            if (action == "clock") report.put("clockAnswer", result.second.content)
            runner.sendStatus(2, Bundle().apply { putString("zhiyu_usb_smoke", report.toString()) })
            if (action == "selfie") org.junit.Assert.assertEquals("A real selfie smoke must publish one actual photo", 1,
                result.second.mediaAttachments.count { it.kind == "image" })
        } finally { watchdog.cancel(); ProactiveForeground.setVisible(oldForeground) }
    }
}
