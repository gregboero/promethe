package dev.promethe.core

import dev.promethe.db.DatabaseFactory
import kotlin.test.*

class FeedbackCollectorTest {
    private lateinit var collector: FeedbackCollector

    @BeforeTest
    fun setup() {
        val database = DatabaseFactory.createInMemory()
        collector = FeedbackCollector(database)
    }

    @Test
    fun testParseScoreEmoji() {
        assertEquals(1.0, collector.parseScore("👍"))
        assertEquals(0.0, collector.parseScore("👎"))
        assertEquals(0.5, collector.parseScore("😐"))
    }

    @Test
    fun testParseScoreWords() {
        assertEquals(1.0, collector.parseScore("yes"))
        assertEquals(1.0, collector.parseScore("good"))
        assertEquals(0.0, collector.parseScore("no"))
        assertEquals(0.0, collector.parseScore("bad"))
        assertEquals(0.5, collector.parseScore("ok"))
        assertEquals(0.5, collector.parseScore("meh"))
    }

    @Test
    fun testParseScoreSymbols() {
        assertEquals(1.0, collector.parseScore("+"))
        assertEquals(0.0, collector.parseScore("-"))
        assertEquals(0.5, collector.parseScore("~"))
    }

    @Test
    fun testParseScoreNumeric_zeroToOne() {
        assertEquals(0.5, collector.parseScore("0.5"))
        assertEquals(0.0, collector.parseScore("0.0"))
        assertEquals(1.0, collector.parseScore("1.0"))
    }

    @Test
    fun testParseScoreNumeric_zeroToTen() {
        assertEquals(0.8, collector.parseScore("8"))
        assertEquals(0.5, collector.parseScore("5"))
        assertEquals(1.0, collector.parseScore("1")) // 1.0 is in 0-1 range, so returned directly
    }

    @Test
    fun testParseScoreInvalid() {
        assertNull(collector.parseScore("hello"))
        assertNull(collector.parseScore("maybe"))
        assertNull(collector.parseScore("-5"))
        assertNull(collector.parseScore("100"))
    }

    @Test
    fun testParseScoreTrimAndCase() {
        assertEquals(1.0, collector.parseScore("  YES  "))
        assertEquals(0.0, collector.parseScore("NO"))
        assertEquals(0.5, collector.parseScore(" Ok "))
    }
}
