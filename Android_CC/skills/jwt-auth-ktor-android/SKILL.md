---
name: jwt-auth-ktor-android
description: End-to-end auth for a Kotlin full-stack app. Ktor server (Argon2id, short-lived JWT access tokens, rotating refresh tokens with reuse detection, Google ID token verification) and Android client (Ktor Auth bearer refresh, Keystore-encrypted DataStore session, session-gated navigation). Use when building or debugging login/session handling.
---

# JWT auth: Ktor server ↔ Android client

## Server
| Piece | Choice |
|---|---|
| Passwords | Argon2id via `com.password4j:password4j` (19 MiB, t=2, p=1); verify a dummy hash for unknown users (timing) |
| Access token | HS256 JWT, 15 min, claims `sub`, `iss`, `aud`, `typ=access`; the verifier requires `typ` |
| Refresh token | 32 random bytes, base64url; store only SHA-256 hex; `family_id` per login |
| Rotation | One transaction: `SELECT … FOR UPDATE`; revoked → revoke family, return Reused; else conditional revoke (`revoked_at IS NULL`) + insert successor |
| Logout | Revoke the token's family; always 204 |
| 401 | Respond with `WWW-Authenticate: Bearer realm="…"` (RFC 6750); Ktor's client relies on it |
| Google | Verify the ID token locally: `JwkProviderBuilder(googleapis certs)`, issuer `accounts.google.com`, audience = Web client IDs; link to an existing account only if `email_verified` |
| Abuse | `RateLimit` on `/auth/*` per IP |

Revoking the family must be committed even though the request fails: return a sealed result from
the transaction and throw *outside* it.

## Android client
```kotlin
install(Auth) {
    bearer {
        loadTokens { store.current()?.let { BearerTokens(it.accessToken, it.refreshToken) } }
        refreshTokens {
            val rt = oldTokens?.refreshToken ?: return@refreshTokens null
            val r = client.post("api/v1/auth/refresh") {
                markAsRefreshTokenRequest(); attributes.put(AuthCircuitBreaker, Unit); setBody(RefreshRequest(rt))
            }
            when {
                r.status.isSuccess() -> r.body<AuthResponse>().also { store.updateTokens(it.accessToken, it.refreshToken) }
                    .let { BearerTokens(it.accessToken, it.refreshToken) }
                r.status == HttpStatusCode.Unauthorized -> { store.clear(); null }   // session over
                else -> null                                                          // server trouble: keep session
            }
        }
        sendWithoutRequest { "auth" !in it.url.pathSegments }
    }
}
```
- Mark login/register/google calls with `attributes.put(AuthCircuitBreaker, Unit)` so a 401 for a wrong password never triggers a refresh.
- After login/logout call `client.authProvider<BearerAuthProvider>()?.clearToken()` (cached tokens).
- Session store: DataStore + AES-256-GCM with a non-exportable Android Keystore key; exclude the file
  in `backup_rules.xml` and `data_extraction_rules.xml`, because the key isn't backed up.
- `SessionManager.state: StateFlow<Loading|LoggedOut|LoggedIn>` drives the root composable; the splash
  screen holds while Loading. Login/logout/expiry need no navigation calls.
- Google: Credential Manager `GetSignInWithGoogleOption(serverClientId)` → `GoogleIdTokenCredential.idToken`
  → POST to the server; `clearCredentialState()` on logout. Hide the button when no client ID is configured.

## Tests worth having
Refresh once + retry; 5 concurrent 401s → exactly 1 refresh; rejected refresh → session cleared;
wrong-password 401 → no refresh; server: replaying a rotated token kills its successor; sessions are
independent per login.
