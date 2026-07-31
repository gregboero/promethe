package dev.promethe.core

import kotlin.test.Test

class DatetimeTest {
    @Test
    fun testClock() {
        try {
            val clazz = Class.forName("kotlinx.datetime.Clock")
            println("=== CLOCK CLASS ===")
            println("Loaded: ${clazz.name}")
            println("Declared Classes:")
            clazz.declaredClasses.forEach { println("  ${it.name}") }
            println("Declared Fields:")
            clazz.declaredFields.forEach { println("  ${it.name} - ${it.type.name}") }
        } catch (e: Exception) {
            println("Failed to load Clock: ${e.message}")
            e.printStackTrace()
        }
    }
}
