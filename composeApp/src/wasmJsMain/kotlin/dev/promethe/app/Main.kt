package dev.promethe.app

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import dev.promethe.app.navigation.PrometheRoute
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import org.w3c.dom.events.Event

/** Read the current route from window.location.hash (e.g. #agents → Agents). */
private fun readHashRoute(): PrometheRoute {
    val hash = window.location.hash.removePrefix("#").trim()
    return PrometheRoute.fromId(hash) ?: PrometheRoute.Sessions
}

/**
 * Installs a JavaScript bridge on `window` for browser agents and automation tools.
 *
 * Exposed API:
 *   - `window.prometheLogin(gatewayUrl, user, password)` → Promise<{success, error?}>
 *   - `window.prometheNavigate(route)` → void  (e.g. "agents", "settings")
 *   - `window.prometheStatus()` → {loggedIn, route, gatewayUrl}
 *   - `window.prometheLogout()` → void
 */
private fun installJsBridge() {
    js(
        """
        window.prometheLogin = async function(gatewayUrl, user, password) {
            try {
                const resp = await fetch(gatewayUrl + '/auth/login', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    credentials: 'include',
                    body: JSON.stringify({ user: user, password: password, clientKind: 'BROWSER' })
                });
                if (!resp.ok) {
                    let errMsg = resp.statusText;
                    try { const e = await resp.json(); errMsg = e.error || errMsg; } catch(_) {}
                    return { success: false, error: errMsg };
                }
                const data = await resp.json();
                localStorage.setItem('promethe_gatewayUrl', gatewayUrl);
                localStorage.setItem('promethe_browserSession', '1');
                // The server owns the HttpOnly cookie; no bearer token is persisted.
                window.location.reload();
                return { success: true };
            } catch(e) {
                return { success: false, error: e.message };
            }
        };

        window.prometheNavigate = function(route) {
            window.location.hash = route;
        };

        window.prometheStatus = function() {
            return {
                loggedIn: localStorage.getItem('promethe_browserSession') === '1',
                route: window.location.hash.replace('#', '') || 'sessions',
                gatewayUrl: localStorage.getItem('promethe_gatewayUrl') || 'http://localhost:8080'
            };
        };

        window.prometheLogout = async function() {
            const gatewayUrl = localStorage.getItem('promethe_gatewayUrl') || '';
            if (gatewayUrl) {
                try {
                    const session = await fetch(gatewayUrl + '/api/v1/auth/session', { credentials: 'include' });
                    const data = session.ok ? await session.json() : {};
                    await fetch(gatewayUrl + '/auth/logout', {
                        method: 'POST',
                        credentials: 'include',
                        headers: data.csrfToken ? { 'X-CSRF-Token': data.csrfToken } : {}
                    });
                } catch (_) {}
            }
            localStorage.removeItem('promethe_browserSession');
            window.location.reload();
        };

        /**
         * Scroll within the Compose canvas by dispatching a mouse wheel event.
         * deltaY > 0  = scroll down, deltaY < 0 = scroll up
         * x, y = coordinates on screen (default: center of viewport)
         */
        window.prometheScroll = function(deltaY, x, y) {
            const el = document.getElementById('promethe');
            if (!el) return;
            const rect = el.getBoundingClientRect();
            const cx = x !== undefined ? x : rect.left + rect.width / 2;
            const cy = y !== undefined ? y : rect.top + rect.height / 2;
            el.dispatchEvent(new WheelEvent('wheel', {
                bubbles: true,
                cancelable: true,
                deltaY: deltaY,
                deltaMode: 0,
                clientX: cx,
                clientY: cy
            }));
        };

        /** Convenience: scroll to approximate a position by scrolling multiple times. */
        window.prometheScrollTo = async function(targetDeltaY, steps, delayMs) {
            steps = steps || 5;
            delayMs = delayMs || 100;
            const perStep = targetDeltaY / steps;
            for (let i = 0; i < steps; i++) {
                window.prometheScroll(perStep);
                await new Promise(r => setTimeout(r, delayMs));
            }
        };

        /**
         * Find an element by its Compose testTag or contentDescription.
         * Tries multiple strategies since Compose WASM doesn't inject data-* attributes.
         *
         * Strategies (in order):
         *  1. data-compose-test-tag attribute (Compose WASM 1.9+)
         *  2. data-testid attribute
         *  3. aria-label matching the tag
         *  4. ARIA tree walk: find node whose accessible name contains the tag
         */
        window.prometheFind = async function(testTag) {
            const root = document.getElementById('promethe') || document.body;

            // 1. data-compose-test-tag (future Compose versions)
            let el = root.querySelector('[data-compose-test-tag="' + testTag + '"]');
            if (el) return { element: el, found: true, method: 'data-compose-test-tag' };

            // 2. data-testid
            el = root.querySelector('[data-testid="' + testTag + '"]');
            if (el) return { element: el, found: true, method: 'data-testid' };

            // 3. aria-label exact match
            el = root.querySelector('[aria-label="' + testTag + '"]');
            if (el) return { element: el, found: true, method: 'aria-label-exact' };

            // 4. aria-label partial match (testTag as substring)
            const allAria = root.querySelectorAll('[aria-label]');
            for (const node of allAria) {
                if (node.getAttribute('aria-label').includes(testTag)) {
                    return { element: node, found: true, method: 'aria-label-partial' };
                }
            }

            // 5. Walk all focusable/interactive elements looking for matching id or name
            const interactive = root.querySelectorAll('button, input, textarea, [role="button"], [role="tab"], [tabindex]');
            for (const node of interactive) {
                if ((node.id && node.id.includes(testTag)) ||
                    (node.name && node.name.includes(testTag)) ||
                    (node.getAttribute('aria-label') || '').includes(testTag)) {
                    return { element: node, found: true, method: 'interactive-scan' };
                }
            }

            return null;
        };

        /**
         * Click on an element identified by its Compose testTag or contentDescription.
         * Falls back to prometheClickAt(x, y) if element cannot be found in DOM.
         */
        window.prometheClick = async function(testTag) {
            const found = await window.prometheFind(testTag);
            if (found && found.element) {
                const rect = found.element.getBoundingClientRect();
                const cx = rect.left + rect.width / 2;
                const cy = rect.top + rect.height / 2;
                found.element.focus();
                found.element.dispatchEvent(new PointerEvent('pointerdown', { bubbles: true, clientX: cx, clientY: cy }));
                found.element.dispatchEvent(new PointerEvent('pointerup',   { bubbles: true, clientX: cx, clientY: cy }));
                found.element.dispatchEvent(new MouseEvent('click',         { bubbles: true, clientX: cx, clientY: cy }));
                return { success: true, method: found.method, x: cx, y: cy };
            }
            return { success: false, error: 'Element "' + testTag + '" not found. Use prometheClickAt(x, y) with coordinates from the Chrome Accessibility tree.' };
        };

        /**
         * Click at explicit screen coordinates (fallback when testTag lookup fails).
         * Dispatches pointer + mouse events at the given position on the Compose canvas.
         */
        window.prometheClickAt = function(x, y) {
            const el = document.getElementById('promethe');
            if (!el) return { success: false, error: 'App root not found' };
            const opts = { bubbles: true, cancelable: true, clientX: x, clientY: y };
            el.dispatchEvent(new PointerEvent('pointerdown', opts));
            el.dispatchEvent(new PointerEvent('pointerup',   opts));
            el.dispatchEvent(new MouseEvent('click',         opts));
            return { success: true, x, y };
        };

        /**
         * Type text into the currently focused Compose text field.
         * Call prometheClick(testTag) first to focus the field, then call prometheType().
         */
        window.prometheType = async function(text, delayMs) {
            delayMs = delayMs || 30;
            const el = document.getElementById('promethe');
            if (!el) return { success: false, error: 'App root not found' };
            for (const char of text) {
                el.dispatchEvent(new KeyboardEvent('keydown',  { key: char, bubbles: true }));
                el.dispatchEvent(new KeyboardEvent('keypress', { key: char, bubbles: true }));
                el.dispatchEvent(new KeyboardEvent('keyup',    { key: char, bubbles: true }));
                if (delayMs > 0) await new Promise(r => setTimeout(r, delayMs));
            }
            return { success: true, typed: text.length + ' characters' };
        };

        /**
         * Press a special key (Enter, Backspace, Escape, Tab, ArrowDown, etc.)
         */
        window.prometheKey = function(key) {
            const el = document.getElementById('promethe');
            if (!el) return { success: false };
            el.dispatchEvent(new KeyboardEvent('keydown', { key, bubbles: true, cancelable: true }));
            el.dispatchEvent(new KeyboardEvent('keyup',   { key, bubbles: true, cancelable: true }));
            return { success: true, key };
        };

        console.log('[Promethe] JS Bridge ready — prometheLogin / prometheNavigate / prometheScroll / prometheScrollTo / prometheClick / prometheClickAt / prometheType / prometheKey / prometheStatus / prometheLogout');
    """,
    )
}

@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    // Install JS bridge first so it's available immediately
    installJsBridge()

    val root = document.getElementById("promethe") ?: return
    val browserSessionHint = window.localStorage.getItem("promethe_browserSession") == "1"
    val initialRoute = readHashRoute()

    // Guard flag: suppress the hashchange event that fires when the app itself
    // updates window.location.hash via onNavigate, preventing an echo loop.
    var suppressNextHashChange = false

    // Flow that emits when the browser hash changes (back/forward, manual edit)
    val hashChanges = callbackFlow<PrometheRoute> {
        val listener: (Event) -> Unit = {
            if (suppressNextHashChange) {
                suppressNextHashChange = false
            } else {
                trySend(readHashRoute())
            }
        }
        window.addEventListener("hashchange", listener)
        awaitClose { window.removeEventListener("hashchange", listener) }
    }

    ComposeViewport(root) {
        App(
            browserSessionHint = browserSessionHint,
            isBrowserClient = true,
            initialRoute = initialRoute,
            onNavigate = { routeId ->
                suppressNextHashChange = true
                window.location.hash = routeId
            },
            externalNavigation = hashChanges,
        )
    }
}
