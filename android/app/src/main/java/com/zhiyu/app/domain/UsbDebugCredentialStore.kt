package com.zhiyu.app.domain

/** The same local backend via ADB reverse; never redirects a custom or release connection. */
class UsbDebugCredentialStore(
    private val delegate: CredentialStore,
    private val enabled: Boolean,
) : CredentialStore by delegate {
    @Synchronized override fun read(): SavedConnection? {
        val saved = delegate.read() ?: return null
        if (!enabled || saved.serverUrl != "http://10.0.2.2:8791") return saved
        val migrated = saved.copy(serverUrl = "http://127.0.0.1:8791")
        return if (delegate.compareAndWrite(saved, migrated)) migrated else delegate.read()
    }

    @Synchronized override fun write(connection: SavedConnection) = delegate.write(connection)
    @Synchronized override fun clear() = delegate.clear()
    override fun compareAndWrite(expected: SavedConnection?, updated: SavedConnection) = delegate.compareAndWrite(expected, updated)
}
