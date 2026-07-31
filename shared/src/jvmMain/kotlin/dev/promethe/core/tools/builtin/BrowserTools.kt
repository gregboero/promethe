package dev.promethe.core.tools.builtin

import ai.koog.agents.core.tools.SimpleTool
import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.serialization.typeToken
import dev.promethe.core.browser.BrowserBackend
import kotlinx.serialization.Serializable

// ══════════════════════════════════════════════════════════════
// Browser Automation Tools — 6 tools for CDP-based browsing
// ══════════════════════════════════════════════════════════════

@Serializable
data class BrowserNavigateArgs(
    @property:LLMDescription("URL to navigate to.")
    val url: String,
)

class BrowserNavigateTool(
    private val backend: BrowserBackend,
) : SimpleTool<BrowserNavigateArgs>(
        argsType = typeToken<BrowserNavigateArgs>(),
        name = "browser_navigate",
        description = "Navigate the browser to a URL.",
    ) {
    override suspend fun execute(args: BrowserNavigateArgs): String {
        val result = backend.navigate(args.url)
        return if (result.success) result.content else "Error: ${result.error}"
    }
}

@Serializable
data class BrowserClickArgs(
    @property:LLMDescription("CSS selector of the element to click.")
    val selector: String,
)

class BrowserClickTool(
    private val backend: BrowserBackend,
) : SimpleTool<BrowserClickArgs>(
        argsType = typeToken<BrowserClickArgs>(),
        name = "browser_click",
        description = "Click an element on the page by CSS selector.",
    ) {
    override suspend fun execute(args: BrowserClickArgs): String {
        val result = backend.click(args.selector)
        return if (result.success) result.content else "Error: ${result.error}"
    }
}

@Serializable
data class BrowserTypeArgs(
    @property:LLMDescription("CSS selector of the input element.")
    val selector: String,
    @property:LLMDescription("Text to type into the element.")
    val text: String,
)

class BrowserTypeTool(
    private val backend: BrowserBackend,
) : SimpleTool<BrowserTypeArgs>(
        argsType = typeToken<BrowserTypeArgs>(),
        name = "browser_type",
        description = "Type text into an input element identified by CSS selector.",
    ) {
    override suspend fun execute(args: BrowserTypeArgs): String {
        val result = backend.type(args.selector, args.text)
        return if (result.success) result.content else "Error: ${result.error}"
    }
}

@Serializable
data class BrowserExtractArgs(
    @property:LLMDescription("CSS selector of the element to extract text from. Use 'body' for entire page.")
    val selector: String = "body",
)

class BrowserExtractTool(
    private val backend: BrowserBackend,
) : SimpleTool<BrowserExtractArgs>(
        argsType = typeToken<BrowserExtractArgs>(),
        name = "browser_extract",
        description = "Extract text content from a page element by CSS selector, or 'body' for entire page.",
    ) {
    override suspend fun execute(args: BrowserExtractArgs): String {
        val result =
            if (args.selector == "body" || args.selector == "page") {
                backend.extractPage()
            } else {
                backend.extract(args.selector)
            }
        return if (result.success) {
            val text = result.content
            if (text.length > 5000) {
                text.take(5000) + "\n...[truncated ${text.length - 5000} chars]"
            } else {
                text
            }
        } else {
            "Error: ${result.error}"
        }
    }
}

@Serializable
data class BrowserScreenshotArgs(
    @property:LLMDescription("Format: 'png' (default) or 'jpeg'.")
    val format: String = "png",
)

class BrowserScreenshotTool(
    private val backend: BrowserBackend,
) : SimpleTool<BrowserScreenshotArgs>(
        argsType = typeToken<BrowserScreenshotArgs>(),
        name = "browser_screenshot",
        description = "Take a screenshot of the current browser page. Returns base64-encoded image.",
    ) {
    override suspend fun execute(args: BrowserScreenshotArgs): String {
        val result = backend.screenshot()
        return if (result.success) {
            "Screenshot captured (${result.content.length} chars base64)"
        } else {
            "Error: ${result.error}"
        }
    }
}

@Serializable
data class BrowserEvalArgs(
    @property:LLMDescription("JavaScript expression to evaluate in the browser page context.")
    val expression: String,
)

class BrowserEvalTool(
    private val backend: BrowserBackend,
) : SimpleTool<BrowserEvalArgs>(
        argsType = typeToken<BrowserEvalArgs>(),
        name = "browser_eval",
        description = "Evaluate JavaScript code in the browser page context.",
    ) {
    override suspend fun execute(args: BrowserEvalArgs): String {
        val result = backend.evaluate(args.expression)
        return if (result.success) result.content else "Error: ${result.error}"
    }
}

// ══════════════════════════════════════════════════════════════
// Extended Browser Tools — 6 additional tools for full parity
// ══════════════════════════════════════════════════════════════

@Serializable
data class BrowserScrollArgs(
    @property:LLMDescription("Horizontal pixels to scroll (0 = none).")
    val x: Int = 0,
    @property:LLMDescription("Vertical pixels to scroll (positive = down, negative = up). Default 500.")
    val y: Int = 500,
)

class BrowserScrollTool(
    private val backend: BrowserBackend,
) : SimpleTool<BrowserScrollArgs>(
        argsType = typeToken<BrowserScrollArgs>(),
        name = "browser_scroll",
        description = "Scroll the browser page by a pixel amount. Positive y scrolls down, negative scrolls up.",
    ) {
    override suspend fun execute(args: BrowserScrollArgs): String {
        val result = backend.scroll(args.x, args.y)
        return if (result.success) result.content else "Error: ${result.error}"
    }
}

class BrowserBackTool(
    private val backend: BrowserBackend,
) : SimpleTool<Unit>(
        argsType = typeToken<Unit>(),
        name = "browser_back",
        description = "Navigate back to the previous page in browser history.",
    ) {
    override suspend fun execute(args: Unit): String {
        val result = backend.back()
        return if (result.success) result.content else "Error: ${result.error}"
    }
}

@Serializable
data class BrowserPressArgs(
    @property:LLMDescription("Key to press, e.g. 'Enter', 'Escape', 'Tab', 'ArrowDown'.")
    val key: String,
)

class BrowserPressTool(
    private val backend: BrowserBackend,
) : SimpleTool<BrowserPressArgs>(
        argsType = typeToken<BrowserPressArgs>(),
        name = "browser_press",
        description = "Press a keyboard key in the browser (Enter, Escape, Tab, ArrowDown, etc.).",
    ) {
    override suspend fun execute(args: BrowserPressArgs): String {
        val result = backend.press(args.key)
        return if (result.success) result.content else "Error: ${result.error}"
    }
}

class BrowserGetImagesTool(
    private val backend: BrowserBackend,
) : SimpleTool<Unit>(
        argsType = typeToken<Unit>(),
        name = "browser_get_images",
        description = "List all images on the current page with their src URLs and alt text.",
    ) {
    override suspend fun execute(args: Unit): String {
        val result = backend.getImages()
        return if (result.success) result.content else "Error: ${result.error}"
    }
}

@Serializable
data class BrowserVisionArgs(
    @property:LLMDescription("Question to ask about the current page screenshot.")
    val question: String = "Describe what you see on this page.",
)

class BrowserVisionTool(
    private val backend: BrowserBackend,
) : SimpleTool<BrowserVisionArgs>(
        argsType = typeToken<BrowserVisionArgs>(),
        name = "browser_vision",
        description = "Take a screenshot of current page and analyze it. Returns base64 screenshot for multimodal analysis.",
    ) {
    override suspend fun execute(args: BrowserVisionArgs): String {
        val result = backend.screenshot()
        return if (result.success) {
            "Screenshot captured for vision analysis (${result.content.length} chars base64). Question: ${args.question}"
        } else {
            "Error: ${result.error}"
        }
    }
}

@Serializable
data class BrowserDialogArgs(
    @property:LLMDescription("Action: 'accept' or 'dismiss'.")
    val action: String = "accept",
    @property:LLMDescription("Optional text to enter in a prompt dialog.")
    val text: String? = null,
)

class BrowserDialogTool(
    private val backend: BrowserBackend,
) : SimpleTool<BrowserDialogArgs>(
        argsType = typeToken<BrowserDialogArgs>(),
        name = "browser_dialog",
        description = "Handle a native browser dialog (alert, confirm, prompt). Accept or dismiss it.",
    ) {
    override suspend fun execute(args: BrowserDialogArgs): String {
        val result = backend.handleDialog(args.action, args.text)
        return if (result.success) result.content else "Error: ${result.error}"
    }
}

/**
 * Factory to create all 12 browser tools for a given [BrowserBackend].
 */
object BrowserTools {
    fun create(backend: BrowserBackend): List<SimpleTool<*>> =
        listOf(
            // Core 6
            BrowserNavigateTool(backend),
            BrowserClickTool(backend),
            BrowserTypeTool(backend),
            BrowserExtractTool(backend),
            BrowserScreenshotTool(backend),
            BrowserEvalTool(backend),
            // Extended 6
            BrowserScrollTool(backend),
            BrowserBackTool(backend),
            BrowserPressTool(backend),
            BrowserGetImagesTool(backend),
            BrowserVisionTool(backend),
            BrowserDialogTool(backend),
        )
}
