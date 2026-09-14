package dev.promethe.core

/** Opaque JSON only. Never load compiled classes in the host JVM. */
internal class KotlinCompilationCache(
    private val maxEntries: Int = 32,
    private val perSession: Int = 4,
    private val maxBytes: Int = 8 * 1024 * 1024,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
    private val ttlMillis: Long = 30 * 60 * 1000,
) {
    init {
        require(maxEntries > 0 && perSession > 0 && maxBytes > 0 && ttlMillis > 0)
    }

    private data class Key(
        val session: String,
        val hash: String,
    )

    private data class Entry(
        val value: String,
        val bytes: Int,
        val created: Long,
    )

    private val entries = LinkedHashMap<Key, Entry>(16, 0.75f, true)
    private var bytes = 0

    @Synchronized fun get(
        session: String,
        key: String,
    ): String? {
        expire()
        return entries[Key(session, key)]?.value
    }

    @Synchronized fun put(
        session: String,
        key: String,
        value: String,
    ) {
        expire()
        remove(session, key)
        val size = value.encodeToByteArray().size
        require(size <= 256 * 1024 && size <= maxBytes)
        while (entries.keys.count { it.session == session } >= perSession) evict(entries.keys.first { it.session == session })
        while (entries.size >= maxEntries || bytes + size > maxBytes) evict(entries.keys.first())
        entries[Key(session, key)] = Entry(value, size, clock())
        bytes += size
    }

    @Synchronized fun remove(
        session: String,
        key: String,
    ) {
        evict(Key(session, key))
    }

    @Synchronized fun clear(session: String) {
        entries.keys.filter { it.session == session }.forEach(::evict)
    }

    @Synchronized fun size(): Int {
        expire()
        return entries.size
    }

    private fun evict(key: Key) {
        bytes -= entries.remove(key)?.bytes ?: 0
    }

    private fun expire() {
        val now = clock()
        entries.filterValues { now - it.created >= ttlMillis }.keys.toList().forEach(::evict)
    }
}
