// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos.core.designsystem

import android.content.ComponentCallbacks2
import com.d4guilar.shelfos.core.reader.ByteBudgetedLruCache
import org.junit.Assert.*
import org.junit.Test

/** L3: the decoded-cover cache's memory-pressure policy uses the SDK's named trim levels and stays bounded. */
@Suppress("DEPRECATION")
class CoverCacheTrimTest {
    private val budget = 24L * 1024 * 1024

    @Test fun trimLevelsMapToKeepHalveOrEmpty() {
        assertEquals(listOf(5, 10, 15, 20, 40, 60, 80), listOf(ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE, ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW,
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL, ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN, ComponentCallbacks2.TRIM_MEMORY_BACKGROUND,
            ComponentCallbacks2.TRIM_MEMORY_MODERATE, ComponentCallbacks2.TRIM_MEMORY_COMPLETE))
        assertNull(coverCacheTrimTarget(ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE, budget))
        assertEquals(budget / 2, coverCacheTrimTarget(ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW, budget))
        for (level in listOf(ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL, ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN, ComponentCallbacks2.TRIM_MEMORY_BACKGROUND,
                ComponentCallbacks2.TRIM_MEMORY_MODERATE, ComponentCallbacks2.TRIM_MEMORY_COMPLETE))
            assertEquals("level $level empties the cache", 0L, coverCacheTrimTarget(level, budget))
    }

    @Test fun trimToEvictsLeastRecentlyUsedUntilWithinTheTargetAndTheCacheRefills() {
        val cache = ByteBudgetedLruCache<String, Long>(100) { it }
        for (k in listOf("a", "b", "c", "d")) cache.put(k, 20)
        cache.get("a") // most recently used now
        cache.trimTo(50)
        assertEquals(40L, cache.sizeBytes)
        assertTrue(cache.contains("a") && cache.contains("d") && !cache.contains("b") && !cache.contains("c"))
        cache.trimTo(0)
        assertEquals(0, cache.count)
        cache.put("e", 30)
        assertEquals("refills normally after a trim", 30L, cache.sizeBytes)
    }
}
