package dev.promethe.app.network

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig

actual fun createPlatformHttpClient(configure: HttpClientConfig<*>.() -> Unit): HttpClient = HttpClient { configure() }
