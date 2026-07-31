package dev.promethe.core

/** Bridge used by integration tools without coupling :shared to :gateway. */
interface OAuthTokenProvider {
    suspend fun getValidToken(
        provider: String,
        ownerId: String = "owner",
    ): String?
}

object OAuthTokenRegistry {
    @Volatile
    private var provider: OAuthTokenProvider? = null

    fun install(tokenProvider: OAuthTokenProvider) {
        provider = tokenProvider
    }

    fun clear(tokenProvider: OAuthTokenProvider? = null) {
        if (tokenProvider == null || provider === tokenProvider) provider = null
    }

    suspend fun get(providerName: String): String? = provider?.getValidToken(providerName)
}
