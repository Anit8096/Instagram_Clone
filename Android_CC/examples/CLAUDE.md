# Project CLAUDE.md template: Compose app + Ktor backend

## Project
<One line: what the app does and for whom.>
Plan and status: `docs/IMPLEMENTATION_PLAN.md`.

## Stack (don't swap without asking)
Android: single-activity Jetpack Compose, MVVM + UDF, <DI: Koin|Hilt>, Ktor Client, kotlinx.serialization,
Navigation 3, <Room/DataStore/WorkManager/Paging as used>, Material 3.
Server (`server/`, a separate Gradle build): Ktor 3, Exposed, Flyway, PostgreSQL, Docker Compose, Testcontainers.

## Commands
- App: `./gradlew :app:compileDebugKotlin`, `:app:testDebugUnitTest`, `:app:lintDebug`
- Server: `cd server && ./gradlew test`; local stack: `docker compose up --build -d`
- Device: `android emulator start <avd>`; if `10.0.2.2` is unreachable, `adb reverse tcp:<port> tcp:<port>`
  and build with `-PapiBaseUrl=http://localhost:<port>`; then `android run --apks=app/build/outputs/apk/debug/app-debug.apk`

## Conventions
- Per milestone: server first, then client; done means tests pass and a device journey passes.
- Write endpoints are idempotent (PUT/DELETE or client-generated UUIDs) so offline clients can replay them.
- Developer-specific values (base URL, OAuth client IDs) come from Gradle properties or `local.properties`.
