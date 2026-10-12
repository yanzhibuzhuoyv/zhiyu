package com.zhiyu.app.data

import com.zhiyu.app.domain.Conversation
import com.zhiyu.app.domain.LocalNightEvent
import com.zhiyu.app.domain.Message

/** Allows scheduling tests to use a synthetic archive without a Key or provider call. */
interface LocalProactiveGateway {
    suspend fun conversations(): List<Conversation>
    suspend fun result(id: String, requestId: String): Message?
    suspend fun history(id: String): List<Message>
    suspend fun send(id: String, requestId: String, event: LocalNightEvent?): Message
}

class RepositoryProactiveGateway(private val repository: LocalChatRepository) : LocalProactiveGateway {
    override suspend fun conversations() = repository.conversations()
    override suspend fun result(id: String, requestId: String) = repository.proactiveResult(id, requestId)
    override suspend fun history(id: String) = repository.proactivePolicyHistory(id)
    override suspend fun send(id: String, requestId: String, event: LocalNightEvent?) = repository.sendProactive(id, requestId, event)
}
