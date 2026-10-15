package com.nexbank.app.core.network

import com.nexbank.app.core.config.CountryConfigProvider
import com.nexbank.app.core.security.SecureTokenStore
import com.nexbank.app.feature.auth.AuthRepository
import kotlinx.coroutines.runBlocking
import okhttp3.Authenticator
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route
import java.util.UUID
import javax.inject.Inject

/**
 * Adds auth + market headers. X-Request-Id lets ROTA engineers correlate an app log line with a
 * backend log line (see docs/ROTA_RUNBOOK.md).
 */
class AuthInterceptor @Inject constructor(
    private val tokens: SecureTokenStore,
    private val country: CountryConfigProvider,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val builder = chain.request().newBuilder()
            .header("X-Country", country.current.country.code)
            .header("X-Request-Id", UUID.randomUUID().toString())
        tokens.token()?.let { builder.header("Authorization", "Bearer $it") }
        return chain.proceed(builder.build())
    }
}

/** On HTTP 401, refresh the token once and replay the request. */
class TokenAuthenticator @Inject constructor(
    private val auth: dagger.Lazy<AuthRepository>, // Lazy breaks any DI cycle
) : Authenticator {
    override fun authenticate(route: Route?, response: Response): Request? {
        if (response.priorResponse != null) return null // already retried once - give up
        val fresh = runBlocking { auth.get().refreshToken() } ?: return null
        return response.request.newBuilder().header("Authorization", "Bearer $fresh").build()
    }
}
