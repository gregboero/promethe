package dev.promethe.core.hooks

import kotlin.test.*
import kotlinx.coroutines.test.runTest

class HookManagerTest {
    @Test
    fun testRegisterUnregister() =
        runTest {
            val manager = HookManager()
            val hook = object : Hook {
                override val id = "test_hook"
                override val events = setOf(HookEvent.BEFORE_TOOL_CALL)

                override suspend fun execute(context: HookContext) = HookResult.Continue
            }

            manager.register(hook)
            assertEquals(listOf("test_hook"), manager.listHooks())

            manager.unregister("test_hook")
            assertTrue(manager.listHooks().isEmpty())
        }

    @Test
    fun testPriorityOrder() =
        runTest {
            val manager = HookManager()
            val executionOrder = mutableListOf<String>()

            val hook2 = object : Hook {
                override val id = "hook2"
                override val events = setOf(HookEvent.BEFORE_TOOL_CALL)
                override val priority = 200

                override suspend fun execute(context: HookContext): HookResult {
                    executionOrder.add("hook2")
                    return HookResult.Continue
                }
            }

            val hook1 = object : Hook {
                override val id = "hook1"
                override val events = setOf(HookEvent.BEFORE_TOOL_CALL)
                override val priority = 50

                override suspend fun execute(context: HookContext): HookResult {
                    executionOrder.add("hook1")
                    return HookResult.Continue
                }
            }

            manager.register(hook2)
            manager.register(hook1) // hook1 has lower priority (50 < 200), so it should execute first

            val ctx = HookContext(HookEvent.BEFORE_TOOL_CALL)
            manager.fire(ctx)

            assertEquals(listOf("hook1", "hook2"), executionOrder)
        }

    @Test
    fun testAbortFlow() =
        runTest {
            val manager = HookManager()
            var hook2Executed = false

            val hook1 = object : Hook {
                override val id = "hook1"
                override val events = setOf(HookEvent.BEFORE_TOOL_CALL)

                override suspend fun execute(context: HookContext) = HookResult.Abort("aborted by test")
            }

            val hook2 = object : Hook {
                override val id = "hook2"
                override val events = setOf(HookEvent.BEFORE_TOOL_CALL)

                override suspend fun execute(context: HookContext): HookResult {
                    hook2Executed = true
                    return HookResult.Continue
                }
            }

            manager.register(hook1)
            manager.register(hook2)

            val ctx = HookContext(HookEvent.BEFORE_TOOL_CALL)
            val result = manager.fire(ctx)

            assertTrue(result is HookResult.Abort)
            assertEquals("aborted by test", (result as HookResult.Abort).reason)
            assertFalse(hook2Executed)
        }
}
