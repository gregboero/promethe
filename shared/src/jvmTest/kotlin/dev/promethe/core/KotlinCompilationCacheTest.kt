package dev.promethe.core

import kotlin.test.*

class KotlinCompilationCacheTest {
    @Test fun `session boundaries and LRU limits preserve only eligible artifacts`() {
        val cache = KotlinCompilationCache(maxEntries = 3, perSession = 2)
        cache.put("one", "a", "A")
        cache.put("one", "b", "B")
        assertNull(cache.get("two", "a"))
        assertEquals("A", cache.get("one", "a"))
        cache.put("one", "c", "C")
        assertNull(cache.get("one", "b"))
        cache.put("two", "a", "different session")
        cache.put("three", "a", "global eviction")
        assertNull(cache.get("one", "a"))
        cache.clear("one")
        assertEquals("different session", cache.get("two", "a"))
        assertEquals(2, cache.size())
    }

    @Test fun `byte budget counts UTF8 and expiry is bounded from insertion`() {
        var now = 0L
        val cache = KotlinCompilationCache(maxBytes = 6, clock = { now }, ttlMillis = 10)
        cache.put("one", "a", "été")
        cache.put("two", "b", "ab")
        assertNull(cache.get("one", "a"))
        now = 9
        assertEquals("ab", cache.get("two", "b"))
        now = 10
        assertNull(cache.get("two", "b"))
        assertEquals(0, cache.size())
        assertFailsWith<IllegalArgumentException> { cache.put("one", "oversized", "1234567") }
    }
}
