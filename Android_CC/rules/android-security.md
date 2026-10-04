# Security

- Tokens/credentials: encrypt at rest (AES-GCM, Android Keystore key); exclude those files from
  backup (`backup_rules.xml` and `data_extraction_rules.xml`).
- Never log tokens: sanitize `Authorization` in HTTP logging; logging only in debug builds.
- Cleartext HTTP only via a debug-only `network_security_config.xml` scoped to `10.0.2.2`/`localhost`.
- Auth calls that can return 401 for bad credentials must bypass token-refresh logic.
- Server: hash passwords with Argon2id; store refresh tokens hashed; rotate and detect reuse;
  rate-limit auth; respond with a uniform error envelope (no stack traces).
- Google sign-in: verify ID tokens server-side (issuer, audience, expiry); trust email only if verified.
- Secrets (`.env`, `google-services.json`, service-account keys) are gitignored; provide `*.example` files.
- Exported components: only the launcher activity unless explicitly needed; validate incoming intents.
