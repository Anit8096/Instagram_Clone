# Insta server

Ktor 3.6 + PostgreSQL 17 (Exposed 1.5, Flyway, HikariCP). Kotlin 2.4.20, Java 21 bytecode.

## Run with Docker Compose (from the repo root)

```sh
cp .env.example .env          # set DATABASE_PASSWORD and a JWT_SECRET of 32+ chars
docker compose up --build
```

- Health: http://localhost:8080/health
- API docs (Swagger UI): http://localhost:8080/docs
- Android emulator base URL: `http://10.0.2.2:8080`

## Run locally against the Compose database

```sh
docker compose up -d postgres
cd server
JWT_SECRET=dev-secret-dev-secret-dev-secret-123 DATABASE_PASSWORD=<from .env> ./gradlew run
```

## Tests

```sh
cd server && ./gradlew test
```

Integration tests start a throwaway Postgres with Testcontainers and are **skipped** when Docker
isn't running. Unit tests always run.

## Configuration (environment variables)

| Variable | Default | Notes |
|---|---|---|
| `JWT_SECRET` | — (required) | ≥ 32 chars, HS256 signing key |
| `DATABASE_URL` / `DATABASE_USER` / `DATABASE_PASSWORD` | `jdbc:postgresql://localhost:5432/insta` / `insta` / `insta` | |
| `GOOGLE_CLIENT_IDS` | empty | Comma-separated accepted audiences; empty disables `/auth/google` (501) |
| `ACCESS_TOKEN_TTL_MINUTES` / `REFRESH_TOKEN_TTL_DAYS` | 15 / 30 | |
| `AUTH_RATE_LIMIT_PER_MINUTE` | 20 | Per client IP, on `/api/v1/auth/*` |
| `MEDIA_ROOT` | `/data/media` | Used from M3 |
| `PORT` | 8080 | |

## Auth model

- Passwords: Argon2id (password4j), parameters stored in the hash.
- Access token: HS256 JWT, 15 min, `typ=access`.
- Refresh token: opaque 256-bit random string, stored as SHA-256. Single-use; each refresh rotates it.
  Presenting an already-used token revokes every token from that login (theft detection).
- Google: ID token verified locally against Google's JWKS (issuer, audience, expiry). Links to an
  existing account only when Google reports the email as verified.
