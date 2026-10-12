package com.zhiyu.app.data

import android.content.Context
import com.zhiyu.app.domain.*
import com.zhiyu.app.notifications.ProactiveNotifications
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.Instant
import java.util.UUID

/** Only existing matching scene images reach send; no fallback renderer is allowed. */
interface LocalDailyShareGateway {
    suspend fun result(id: String, requestId: String): Message?
    suspend fun scene(id: String, now: Instant): LocalDailyScene
    suspend fun picture(id: String, event: LocalDailyShareEvent, usedSha: Set<String>): MediaAttachment?
    suspend fun send(id: String, event: LocalDailyShareEvent, attachment: MediaAttachment): Message
}

class LocalDailyShareService(context: Context, private val credentials: CredentialStore,
    private val gateway: LocalDailyShareGateway = RepositoryDailyShareGateway(context, credentials),
    private val state: LocalDailyShareStore = LocalDailyShareStore(context),
    private val preferencesService: ProactiveService = LocalProactiveService(context, credentials),
    private val enabled: (String) -> Boolean = ProactiveWorkStore(context)::enabled,
    private val permission: () -> Boolean = { ProactiveNotifications.allowed(context) },
    private val now: () -> Instant = Instant::now,
) : ProactiveService {
    override suspend fun preferences(id: String) = preferencesService.preferences(id).let { it.copy(enabled = it.enabled && enabled(id)) }
    override suspend fun savePreferences(id: String, preferences: ProactivePreferences) = preferencesService.savePreferences(id, preferences)
    override suspend fun check(id: String, requestId: String): ProactiveCheck = serial.withLock { withContext(Dispatchers.IO) {
        MediaRules.path(id)
        require(UUID.fromString(requestId).toString() == requestId) { "日常分享请求标识无效" }
        gate(id)?.let { return@withContext it }
        // Recovery precedes date/expiry/visual checks: this message was already committed,
        // and the same pending worker still needs to claim its notification exactly once.
        val remembered = state.aliasEvent(id, requestId) ?: state.event(id, requestId)
        remembered?.let { event -> gateway.result(id, event.requestId)?.let { message ->
            return@withContext completed(id, requestId, event, message)
        } }
        if (remembered != null && !now().isBefore(remembered.expires)) state.finish(id, remembered)
        val instant = now()
        val day = LocalDailyRules.dayId(instant)
        state.prune(id, day)
        val plan = state.plan(id, day) ?: initialize(id, day, instant)
        if (plan.quota == 0) return@withContext idle(plan, id, "zero_quota")
        for (event in plan.events.sortedBy { it.due }) {
            // A retry UUID belongs only to its original event. Once that event
            // expires, release this worker; a new request handles the next slot.
            if (remembered != null && event.requestId != remembered.requestId) continue
            if (event.requestId in state.finished(id, day)) continue
            gateway.result(id, event.requestId)?.let { message ->
                return@withContext completed(id, requestId, event, message)
            }
            if (!now().isBefore(event.expires)) { state.finish(id, event); continue }
            if (!LocalDailySharePolicy.ready(event, now())) continue
            val current = refresh(id, event) ?: continue
            return@withContext attempt(id, requestId, current)
        }
        idle(state.plan(id, day) ?: plan, id, "not_due")
    } }

    private suspend fun initialize(id: String, day: String, instant: Instant): LocalDailySharePlan {
        val binding = credentials.readBinding() ?: throw ApiFailure(401, "请先选定恋人")
        val style = binding.profile?.style ?: "gentle"
        val count = LocalDailySharePolicy.quota(id, style, day)
        val plan = if (count == 0) LocalDailySharePlan(day, 0, emptyList()) else {
            val scene = gateway.scene(id, instant)
            require(scene.dayId == day) { "当天的日轨迹尚未准备好" }
            LocalDailySharePolicy.plan(id, style, scene)
        }
        return state.savePlan(id, plan)
    }

    private suspend fun refresh(id: String, event: LocalDailyShareEvent): LocalDailyShareEvent? {
        val instant = now()
        val scene = gateway.scene(id, instant)
        require(scene.timezone == LocalDailyRules.TIMEZONE && scene.fictional && scene.visualVersion >= 0)
        // Preserve the original chosen activity, UUID, quota and due. A replacement
        // activity or an already elapsed actual slot expires rather than rerolls it.
        if (scene.dayId != event.dayId || event.slot !in scene.slots || LocalDailyRules.current(scene.slots, instant) != event.slot) {
            state.finish(id, event)
            return null
        }
        return state.refresh(id, event.copy(sceneId = scene.sceneId, revision = LocalDailyRules.revision(scene), visualVersion = scene.visualVersion))
    }

    private suspend fun attempt(id: String, requestId: String, event: LocalDailyShareEvent): ProactiveCheck {
        gate(id)?.let { return it }
        val usedSha = state.usedSha(id)
        val image = gateway.picture(id, event, usedSha)
        gate(id)?.let { return it }
        if (!LocalDailySharePolicy.ready(event, now())) {
            state.finish(id, event)
            return idle(requireNotNull(state.plan(id, event.dayId)), id, "expired")
        }
        if (image == null || !matches(image, event, usedSha)) {
            return ProactiveCheck(false, null, now().plusSeconds(900).toString(), "await_image")
        }
        state.rememberRequest(id, requestId, event) // Durable before the paid request or local append.
        val message = gateway.send(id, event, image)
        require(message.mediaAttachments.any { it == image }) { "日常分享回复缺少选定图片" }
        return completed(id, requestId, event, message)
    }

    private suspend fun gate(id: String): ProactiveCheck? {
        val reason = when {
            !enabled(id) || !preferencesService.preferences(id).enabled -> "disabled"
            !permission() -> "permission"
            else -> return null
        }
        return ProactiveCheck(false, null, LocalDailyRules.nextMidnight(now()).toString(), reason)
    }
    private fun matches(image: MediaAttachment, event: LocalDailyShareEvent, usedSha: Set<String>): Boolean {
        try { MediaRules.check(image) } catch (_: IllegalArgumentException) { return false }
        return image.kind == "image" && image.dayId == event.dayId && image.visualVersion == event.visualVersion && image.sha256 !in usedSha
    }
    private fun completed(id: String, requestId: String, event: LocalDailyShareEvent, message: Message): ProactiveCheck {
        require(message.role == "assistant" && message.origin == "daily_share") { "日常分享回复归属无效" }
        val image = message.mediaAttachments.singleOrNull { it.kind == "image" } ?: error("日常分享回复缺少唯一的日常图片")
        require(matches(image, event, emptySet())) { "日常分享回复图片与活动不一致" }
        state.rememberRequest(id, requestId, event)
        state.finish(id, event, image.sha256)
        val plan = requireNotNull(state.plan(id, event.dayId))
        return ProactiveCheck(true, message, LocalDailySharePolicy.nextDue(plan, now(), state.finished(id, event.dayId)).toString(), "sent")
    }
    private fun idle(plan: LocalDailySharePlan, id: String, reason: String) = ProactiveCheck(false, null,
        LocalDailySharePolicy.nextDue(plan, now(), state.finished(id, plan.dayId)).toString(), reason)
    companion object { private val serial = Mutex() }
}

/** Lazy adapter keeps denied/disabled/zero-quota checks away from model generation. */
class RepositoryDailyShareGateway(private val context: Context, private val credentials: CredentialStore,
    private val now: () -> Instant = Instant::now,
) : LocalDailyShareGateway {
    private val media by lazy { LocalDailyShareMedia(context, now) }
    private val repository by lazy { LocalChatRepository(context, credentials, careContext = NativeCareContext(context)::prompt, dailyNow = now) }
    override suspend fun result(id: String, requestId: String) = repository.proactiveResult(id, requestId)
    override suspend fun scene(id: String, now: Instant) = withContext(Dispatchers.IO) { media.scene(id, now) }
    override suspend fun picture(id: String, event: LocalDailyShareEvent, usedSha: Set<String>) =
        withContext(Dispatchers.IO) { media.prepare(id, event, usedSha) }
    override suspend fun send(id: String, event: LocalDailyShareEvent, attachment: MediaAttachment) =
        repository.sendDailyShare(id, event.requestId, event, attachment)
}
