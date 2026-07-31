package dev.promethe.app.a2ui

import dev.promethe.app.a2ui.renderers.*

/**
 * Registers all built-in A2UI widget renderers.
 * Call once at app startup (e.g., in App.kt or DI initialization).
 */
fun registerBuiltInRenderers() {
    A2UIRegistry.register("text", ::TextRenderer)
    A2UIRegistry.register("button", ::ButtonRenderer)
    A2UIRegistry.register("card", ::CardRenderer)
    A2UIRegistry.register("row", ::RowRenderer)
    A2UIRegistry.register("column", ::ColumnRenderer)
    A2UIRegistry.register("form", ::FormRenderer)
    A2UIRegistry.register("input", ::InputRenderer)
    A2UIRegistry.register("table", ::TableRenderer)
    A2UIRegistry.register("table_row", ::TableRowRenderer)
}
