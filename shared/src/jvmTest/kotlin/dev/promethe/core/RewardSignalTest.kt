package dev.promethe.core

import kotlin.test.*

class RewardSignalTest {
    private val reward = RewardSignal()

    @Test
    fun testAdjustConfig_lowScore_increasesTemperature() {
        val config = AgentConfig(temperature = 0.2)
        val adjusted = reward.adjustConfig(0.3, config)
        // 0.2 + 0.1 = ~0.3 (IEEE 754 floating point)
        assertEquals(0.3, adjusted.temperature, 0.001)
    }

    @Test
    fun testAdjustConfig_highScore_decreasesTemperature() {
        val config = AgentConfig(temperature = 0.2)
        val adjusted = reward.adjustConfig(0.9, config)
        // 0.2 - 0.05 = ~0.15
        assertEquals(0.15, adjusted.temperature, 0.001)
    }

    @Test
    fun testAdjustConfig_midScore_noChange() {
        val config = AgentConfig(temperature = 0.5)
        val adjusted = reward.adjustConfig(0.6, config)
        assertEquals(0.5, adjusted.temperature, 0.001)
    }

    @Test
    fun testAdjustConfig_coercesMax() {
        val config = AgentConfig(temperature = 0.85)
        val adjusted = reward.adjustConfig(0.1, config)
        assertEquals(0.9, adjusted.temperature, 0.001) // max 0.9
    }

    @Test
    fun testAdjustConfig_coercesMin() {
        val config = AgentConfig(temperature = 0.05)
        val adjusted = reward.adjustConfig(0.95, config)
        assertEquals(0.05, adjusted.temperature, 0.001) // can't go below 0.05
    }

    @Test
    fun testShouldAllowSynthesis_nullScore_allows() {
        assertTrue(reward.shouldAllowSynthesis(null))
    }

    @Test
    fun testShouldAllowSynthesis_highScore_allows() {
        assertTrue(reward.shouldAllowSynthesis(0.8))
        assertTrue(reward.shouldAllowSynthesis(0.6))
    }

    @Test
    fun testShouldAllowSynthesis_lowScore_blocks() {
        assertFalse(reward.shouldAllowSynthesis(0.3))
        assertFalse(reward.shouldAllowSynthesis(0.0))
        assertFalse(reward.shouldAllowSynthesis(0.59))
    }
}
