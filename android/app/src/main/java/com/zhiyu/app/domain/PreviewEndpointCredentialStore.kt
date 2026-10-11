package com.zhiyu.app.domain

/** Explicit debug preview migration to the same backend; custom and release connections stay intact. */
class PreviewEndpointCredentialStore(
    private val delegate: CredentialStore,
    enabled: Boolean,
    previewEndpoint: String,
) : CredentialStore by delegate {
    private val targetEndpoint = if (enabled) ConnectionRules.normalizeUrl(previewEndpoint, false) else null

    @Synchronized override fun read(): SavedConnection? {
        val saved = delegate.read() ?: return null
        val target = targetEndpoint ?: return saved
        if (saved.serverUrl !in setOf("http://127.0.0.1:8791", "http://10.0.2.2:8791")) return saved
        val migrated = saved.copy(serverUrl = target, session = null)
        return if (delegate.compareAndWrite(saved, migrated)) migrated else delegate.read()
    }

    @Synchronized override fun write(connection: SavedConnection) = delegate.write(connection)
    @Synchronized override fun clear() = delegate.clear()
    override fun compareAndWrite(expected: SavedConnection?, updated: SavedConnection) = delegate.compareAndWrite(expected, updated)
}
