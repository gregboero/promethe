package dev.promethe.core

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class HarnessExposurePolicyTest {
    @Test fun `require repeated large observations and enough remaining work`() {
        val policy = HarnessExposurePolicy(estimatedExposureBytesPerCall = 0)
        assertFalse(policy.shouldExpose(emptyList(), 8))
        assertFalse(policy.shouldExpose(listOf(2048), 7))
        assertFalse(policy.shouldExpose(listOf(1023, 1023, 16), 5))
        assertTrue(policy.shouldExpose(listOf(1024, 1024), 6))
        assertTrue(policy.shouldExpose(listOf(1024, 16, 1024), 4))
        assertFalse(policy.shouldExpose(listOf(2048, 2048), 3))
        assertFalse(policy.shouldExpose(listOf(2048, 2048), 0))
    }

    @Test fun `default hides tools when even complete removal cannot amortize exposure`() {
        val policy = HarnessExposurePolicy()
        assertFalse(policy.shouldExpose(listOf(1200, 1200), 6))
        assertTrue(policy.shouldExpose(listOf(8192, 8192), 6))
        assertFalse(policy.shouldExpose(listOf(8192, 8192), 3))
        assertFalse(policy.shouldExpose(listOf(8192, 8192, 1, 1, 1, 1), 4))
        assertFalse(HarnessExposurePolicy(estimatedExposureBytesPerCall = 10, mutationSetupCalls = 0, minimumObservationBytes = 1).shouldExpose(listOf(5, 5), 4))
        assertTrue(policy.shouldExpose(listOf(Int.MAX_VALUE, Int.MAX_VALUE), Int.MAX_VALUE))
    }

    @Test fun `reject invalid measurements and configuration`() {
        assertFailsWith<IllegalArgumentException> { HarnessExposurePolicy(minimumObservationBytes = 0) }
        assertFailsWith<IllegalArgumentException> { HarnessExposurePolicy(estimatedExposureBytesPerCall = -1) }
        assertFailsWith<IllegalArgumentException> { HarnessExposurePolicy(mutationSetupCalls = -1) }
        assertFailsWith<IllegalArgumentException> { HarnessExposurePolicy().shouldExpose(listOf(-1), 4) }
        assertFailsWith<IllegalArgumentException> { HarnessExposurePolicy().shouldExpose(emptyList(), -1) }
    }
}
