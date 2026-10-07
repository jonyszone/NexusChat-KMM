package com.example.nexuschat.server.auth

import io.ktor.server.application.ApplicationCall

/** Development-only identity boundary. Replace with verified token/session validation before production. */
interface SessionAuthenticator { fun authenticate(call: ApplicationCall): AuthenticatedSession? }
data class AuthenticatedSession(val userId: String)

class DevelopmentSessionAuthenticator : SessionAuthenticator {
    override fun authenticate(call: ApplicationCall): AuthenticatedSession? =
        call.request.headers["X-Dev-User-Id"]?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{1,64}")) }?.let(::AuthenticatedSession)
}
