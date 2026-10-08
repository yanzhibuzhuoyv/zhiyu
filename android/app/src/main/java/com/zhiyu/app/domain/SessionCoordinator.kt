package com.zhiyu.app.domain

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class SessionCoordinator(
    private val store: CredentialStore,
    private val auth: AuthService,
    private val now: () -> Long = { System.currentTimeMillis() / 1000 },
) {
    private val lock = Mutex()
    fun saved(): SavedConnection? {
        val connection = store.read() ?: return null
        checkIdentity(connection, connection.session)
        if (connection.session != null) retainBinding(connection)
        return connection
    }
    fun clear() = store.clear()
    fun binding() = store.readBinding()

    private fun checkIdentity(connection: SavedConnection, session: AuthSession? = null) {
        val binding = store.readBinding()
        require(binding == null || (binding.providerId == connection.providerId && binding.model == connection.model)) {
            "已绑定的 AI 不能更换公司或模型"
        }
        require(session == null || (session.providerId == connection.providerId && session.model == connection.model)) {
            "服务返回的 AI 与选择不一致"
        }
    }
    private fun retainBinding(connection: SavedConnection) {
        if (store.readBinding() == null) store.writeBinding(CompanionBinding(connection.providerId, connection.model, profileConfigured = false))
    }
    fun adoptLegacyProfile(profile: CompanionProfile?) {
        val binding = store.readBinding() ?: return
        if (!binding.profileConfigured) store.writeBinding(binding.copy(profile = CompanionRules.normalize(profile), profileConfigured = true))
    }
    fun updateTraining(profile: CompanionProfile?) {
        val binding = store.readBinding() ?: throw IllegalStateException("请先绑定 AI")
        val normalized = CompanionRules.normalize(profile)
        CompanionRules.checkChange(binding, normalized)
        if (normalized?.mode == "custom") store.writeBinding(binding.copy(profile = normalized))
    }

    suspend fun login(url: String, provider: String, key: String, model: String,
                      profile: CompanionProfile? = null,
                      prepare: (suspend (AuthSession) -> CompanionProfile?)? = null) = lock.withLock {
        require(provider.isNotBlank()) { "请选择服务商" }
        require(key.isNotBlank()) { "请输入 API Key" }
        require(model.isNotBlank()) { "请输入模型名称" }
        val draft = SavedConnection(url, provider, key.trim(), model.trim())
        checkIdentity(draft)
        val binding = store.readBinding()
        val requested = CompanionRules.normalize(profile ?: binding?.profile)
        CompanionRules.checkChange(binding, requested)
        val session = auth.authenticate(draft)
        checkIdentity(draft, session)
        val accepted = CompanionRules.normalize(if (prepare == null) requested else prepare(session))
        CompanionRules.checkChange(binding, accepted)
        if (binding == null) store.writeBinding(CompanionBinding(provider, session.model, accepted))
        else if (!binding.profileConfigured) store.writeBinding(binding.copy(profile = accepted, profileConfigured = true))
        else if (accepted?.mode == "custom") store.writeBinding(binding.copy(profile = accepted))
        store.write(draft.copy(model = session.model, session = session))
    }

    suspend fun token(force: Boolean = false, failedToken: String? = null): String = lock.withLock {
        val connection = store.read() ?: throw ApiFailure(401, "请先连接服务商")
        val session = connection.session
        checkIdentity(connection, session)
        val changedSinceFailure = failedToken != null && session?.token != failedToken
        if (session != null && session.expiresAt > now() + 30 && (!force || changedSinceFailure)) {
            retainBinding(connection)
            return@withLock session.token
        }
        val updated = try { auth.authenticate(connection) } catch (error: ApiFailure) {
            throw ApiFailure(error.status, "会话已失效，API Key 重新验证失败：${error.message}")
        }
        checkIdentity(connection, updated)
        if (store.compareAndWrite(connection, connection.copy(session = updated))) {
            retainBinding(connection)
            return@withLock updated.token
        }
        val latest = store.read()
        if (latest != null && latest.copy(session = connection.session) == connection &&
            latest.session != null && latest.session.expiresAt > now() + 30) {
            checkIdentity(latest, latest.session)
            retainBinding(latest)
            return@withLock latest.session.token
        }
        throw ApiFailure(401, "本机已不再连接，请重新连接")
    }

    suspend fun <T> authorized(block: suspend (String) -> T): T {
        val current = token()
        return try { block(current) } catch (error: ApiFailure) {
            if (error.status != 401) throw error
            block(token(force = true, failedToken = current))
        }
    }
}
