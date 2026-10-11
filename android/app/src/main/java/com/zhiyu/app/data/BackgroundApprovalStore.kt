package com.zhiyu.app.data

import android.content.Context
import com.zhiyu.app.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/** Permission binds the checked bytes and rule version; a title or filename cannot grant it. */
internal class BackgroundApprovalStore(context: Context) {
    private val root = File(context.applicationContext.filesDir, "background_approvals")
    private class Flight(val mutex: Mutex = Mutex(), var callers: Int = 0)
    companion object {
        private val registryLock = Any()
        private val flights = mutableMapOf<String, Flight>()
        private val states = setOf("checking", "approved", "rejected", "unknown")
    }

    fun approved(image: AgnesGeneratedImage): Boolean = synchronized(LocalMediaStore.lock) {
        state(digest(image)) == "approved"
    }

    /** Claim before a potentially charged call. Interrupted claims never automatically retry. */
    suspend fun checked(image: AgnesGeneratedImage, approval: BackgroundImageApproval): Boolean {
        val sha = withContext(Dispatchers.Default) { digest(image) }
        val key = "${root.absolutePath}/${BackgroundContentRules.SCHEMA}/$sha"
        val flight = synchronized(registryLock) { flights.getOrPut(key) { Flight() }.also { it.callers++ } }
        try {
            return flight.mutex.withLock {
                val previous = withContext(Dispatchers.IO) { synchronized(LocalMediaStore.lock) { state(sha) } }
                if (previous != null) return@withLock previous == "approved"
                withContext(Dispatchers.IO) { save(sha, "checking") }
                try {
                    val permitted = approval.allows(image)
                    currentCoroutineContext().ensureActive()
                    withContext(Dispatchers.IO) { save(sha, if (permitted) "approved" else "rejected") }
                    permitted
                } catch (cancelled: CancellationException) {
                    try { withContext(NonCancellable + Dispatchers.IO) { save(sha, "unknown") } }
                    catch (storage: Exception) { cancelled.addSuppressed(storage) }
                    throw cancelled
                } catch (_: Exception) {
                    withContext(NonCancellable + Dispatchers.IO) { save(sha, "unknown") }
                    false
                }
            }
        } finally {
            synchronized(registryLock) {
                flight.callers--
                if (flight.callers == 0 && flights[key] === flight) flights.remove(key)
            }
        }
    }

    private fun digest(image: AgnesGeneratedImage): String {
        val bytes = image.bytes
        require(AgnesImageProtocol.imageMime(bytes) == image.mimeType) { "生成背景类型无效" }
        return LocalMemoryCodec.sha(bytes)
    }

    private fun state(sha: String): String? {
        val file = File(root, "$sha.json")
        if (!file.isFile) return null
        require(file.length() in 1..512L)
        val value = AgnesImageJson.parse(file.readText(Charsets.UTF_8)) as? Map<*, *>
            ?: throw IllegalArgumentException("背景检查收据无效")
        require(value["sha256"] == sha) { "背景检查收据校验不一致" }
        require(value["schema"] is String && (value["schema"] as String).length in 1..64) { "背景检查收据版本无效" }
        if (value["schema"] != BackgroundContentRules.SCHEMA) return null
        // Preserve valid positive receipts from the first trial. Nothing else implicitly approves.
        if (value.keys == setOf("schema", "sha256", "approved") && value["approved"] == true) return "approved"
        val storedState = value["state"] as? String ?: throw IllegalArgumentException("背景检查收据状态无效")
        require(value.keys == setOf("schema", "sha256", "state") && storedState in states) { "背景检查收据状态无效" }
        return storedState
    }

    fun approve(image: AgnesGeneratedImage) = save(digest(image), "approved")

    private fun save(sha: String, state: String) = synchronized(LocalMediaStore.lock) {
        require(state in states)
        LocalMediaStore.write(File(root, "$sha.json"),
            "{\"schema\":\"${BackgroundContentRules.SCHEMA}\",\"sha256\":\"$sha\",\"state\":\"$state\"}".toByteArray())
    }
}
