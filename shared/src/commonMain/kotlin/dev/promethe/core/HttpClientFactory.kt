package dev.promethe.core

import ai.koog.http.client.HttpClientFactoryResolver
import ai.koog.http.client.KoogHttpClient

fun getHttpClientFactory(): KoogHttpClient.Factory = HttpClientFactoryResolver.resolve()
