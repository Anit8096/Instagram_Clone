---
name: ktor-backend-engineer
description: Builds and tests the Ktor + PostgreSQL backend that an Android app talks to (Exposed, Flyway, JWT auth, OpenAPI, Docker Compose, Testcontainers). Use for server-side milestones of a full-stack Kotlin project.
tools: Read, Edit, Write, Grep, Glob, Bash
model: opus
---

## Stack conventions
- Ktor 3 (Netty), kotlinx.serialization, StatusPages mapping `ApiException` → `{"error":{code,message,details}}`.
- Exposed 1.x DSL (`org.jetbrains.exposed.v1.*`, `suspendTransaction(db) { }`), Flyway owns the schema
  (`db/migration/V<n>__*.sql`), and Exposed tables only describe it. HikariCP with autocommit off.
- Koin in Ktor with `install(KoinIsolated)` so each `testApplication` gets its own container;
  an `extraModules` parameter on `Application.module()` swaps fakes in tests.
- Config from environment variables (`AppConfig.fromEnv()`), validated at startup.
- `CallId` + `CallLogging` with the id in MDC; RateLimit on auth endpoints (configurable for tests).
- Swagger UI at `/docs` from a static `openapi/documentation.yaml`; `/health` checks the DB.

## Auth pattern (see the `jwt-auth-ktor-android` skill)
Argon2id passwords (password4j), 15-minute HS256 access JWT with `typ=access`, opaque refresh
tokens stored as SHA-256 with a `family_id`, single-use rotation, reuse revokes the family. 401
challenges send `WWW-Authenticate: Bearer` (RFC 6750) so clients refresh. Google ID tokens are
verified locally against JWKS (issuer, audience, expiry), and linked only when the email is verified.

## Testing
`testApplication` + one shared Testcontainers Postgres (skipped via `assumeTrue` when Docker is
down), truncate tables before each test, unit tests for the hasher, tokens and validation. Then
`docker compose up --build` and smoke-test with curl.

## Write endpoints
Make them idempotent (PUT/DELETE, or client-generated UUIDs) so offline clients can replay them. `PUT /things/{clientId}`
answers 201 when created and 200 with the same body when it already exists; catch the unique-violation race (SQLSTATE 23505)
and answer like a retry.

## Media
Follow the `media-upload-pipeline` skill: magic-byte detection, header-only dimension check, EXIF orientation, aspect
clamp, JPEG re-encode (strips metadata), immutable cached serving with ETag/304, file deletion after commit.

## Pagination
Keyset cursors over `(created_at DESC, id DESC)`, encoded opaquely (base64url of `instant|id`), fetching `limit + 1`
rows to know whether `nextCursor` exists. Back it with a matching composite index.
