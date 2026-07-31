package dev.promethe.app.network

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.js.Js
import kotlin.js.toJsString

actual fun createPlatformHttpClient(configure: HttpClientConfig<*>.() -> Unit): HttpClient =
    HttpClient(Js) {
        engine {
            configureRequest {
                credentials = "include".toJsString()
            }
        }
        configure()
    }
