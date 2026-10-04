package com.zhiyu.app.domain

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class SessionCoordinator(
    private val store: CredentialStore,
    private val auth: AuthService,
    private val now: () -> Long = { System.currentTimeMillis() / 1000 },
) {
    private val lock = Mutex()
    fun saved() = store.read()
    fun clear() = store.clear()

    suspend fun login(url: String, provider: String, key: String, model: String) = lock.withLock {
        require(provider.isNotBlank()) { "请选择服务商" }
        require(key.isNotBlank()) { "请输入 API Key" }
        require(model.isNotBlank()) { "请输入模型名称" }
        val draft = SavedConnection(url, provider, key.trim(), model.trim())
        val session = auth.authenticate(draft)
        store.write(draft.copy(model = session.model, session = session))
    }

    suspend fun token(force: Boolean = false, failedToken: String? = null): String = lock.withLock {
        val connection = store.read() ?: throw ApiFailure(401, "请先连接服务商")
        val session = connection.session
        val changedSinceFailure = failedToken != null && session?.token != failedToken
        if (session != null && session.expiresAt > now() + 30 && (!force || changedSinceFailure)) {
            return@withLock session.token
        }
        val updated = try { auth.authenticate(connection) } catch (error: ApiFailure) {
            throw ApiFailure(error.status, "会话已失效，API Key 重新验证失败：${error.message}")
        }
        store.write(connection.copy(session = updated))
        updated.token
    }

    suspend fun <T> authorized(block: suspend (String) -> T): T {
        val current = token()
        return try { block(current) } catch (error: ApiFailure) {
            if (error.status != 401) throw error
            block(token(force = true, failedToken = current))
        }
    }
}
