package dev.promethe.core.browser

/**
 * BrowserBackend — abstraction for browser automation.
 *
 * Implementations include:
 * - [CdpBrowserBackend]: Chrome DevTools Protocol (local or remote Chrome)
 * - Future: BrowserbaseBrowserBackend (cloud headless)
 */
interface BrowserBackend {
    /** Navigate to a URL and wait for load. */
    suspend fun navigate(url: String): BrowserResult

    /** Click an element by CSS selector. */
    suspend fun click(selector: String): BrowserResult

    /** Type text into an input field identified by CSS selector. */
    suspend fun type(
        selector: String,
        text: String,
    ): BrowserResult

    /** Extract text content from an element by CSS selector. */
    suspend fun extract(selector: String): BrowserResult

    /** Extract the full page text content. */
    suspend fun extractPage(): BrowserResult

    /** Take a screenshot (returns base64-encoded PNG). */
    suspend fun screenshot(): BrowserResult

    /** Evaluate arbitrary JavaScript in the page context. */
    suspend fun evaluate(expression: String): BrowserResult

    /** Close the browser/tab. */
    suspend fun close()

    /** Check if the browser is connected. */
    fun isConnected(): Boolean

    // ── Extended browser operations (default via JS evaluate) ──

    /** Scroll the page by pixel amount (positive = down, negative = up). */
    suspend fun scroll(
        x: Int = 0,
        y: Int = 500,
    ): BrowserResult = evaluate("window.scrollBy($x, $y); `Scrolled by ($x, $y)`")

    /** Navigate back in browser history. */
    suspend fun back(): BrowserResult = evaluate("history.back(); 'Navigated back'")

    /** Press a keyboard key (e.g. Enter, Escape, Tab). */
    suspend fun press(key: String): BrowserResult = evaluate("document.dispatchEvent(new KeyboardEvent('keydown', {key: '$key'})); 'Pressed $key'")

    /** Get all image URLs on the current page. */
    suspend fun getImages(): BrowserResult = evaluate("JSON.stringify(Array.from(document.images).map(i => ({src: i.src, alt: i.alt})))")

    /** Handle a native browser dialog (alert/confirm/prompt). */
    suspend fun handleDialog(
        action: String = "accept",
        text: String? = null,
    ): BrowserResult = BrowserResult(success = true, content = "Dialog $action (handled via CDP event listener)")
}

data class BrowserResult(
    val success: Boolean,
    val content: String = "",
    val error: String? = null,
)
