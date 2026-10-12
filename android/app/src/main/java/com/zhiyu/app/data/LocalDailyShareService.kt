package com.zhiyu.app.data

import android.content.Context
import com.zhiyu.app.domain.*
import com.zhiyu.app.notifications.ProactiveNotifications
import java.time.Instant

/** Only existing matching scene images reach send; no fallback renderer is allowed. */
interface LocalDailyShareGateway {
    suspend fun result(id: String, requestId: String): Message?
    suspend fun scene(id: String, now: Instant): LocalDailyScene
    suspend fun picture(id: String, event: LocalDailyShareEvent, usedSha: Set<String>): MediaAttachment?
    suspend fun send(id: String, event: LocalDailyShareEvent, attachment: MediaAttachment): Message
}

@Suppress("UNUSED_PARAMETER")
class LocalDailyShareService(context: Context, private val credentials: CredentialStore,
    private val gateway: LocalDailyShareGateway,
    private val state: LocalDailyShareStore = LocalDailyShareStore(context),
    private val preferencesService: ProactiveService = LocalProactiveService(context, credentials),
    private val enabled: (String) -> Boolean = ProactiveWorkStore(context)::enabled,
    private val permission: () -> Boolean = { ProactiveNotifications.allowed(context) },
    private val now: () -> Instant = Instant::now,
) : ProactiveService {
    override suspend fun preferences(id: String): ProactivePreferences = error("daily share service not implemented")
    override suspend fun savePreferences(id: String, preferences: ProactivePreferences): ProactivePreferences = error("daily share service not implemented")
    override suspend fun check(id: String, requestId: String): ProactiveCheck = error("daily share service not implemented")
}
