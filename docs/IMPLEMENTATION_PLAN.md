# Insta — v1 Implementation Plan

Source of truth for scope: the approved v1 spec (auth, profiles, single-photo posts, follows,
chronological feed, likes, comments, 1:1 real-time DMs, in-app + FCM notifications, user search +
explore, delete post/comment/account). Out of scope: stories, reels, carousels, private accounts,
groups, blocking, password reset.

Versions were checked against Google Maven, Maven Central, and the Gradle Plugin Portal on **2026-10-04**.

## Status

| Milestone | State | Notes |
|---|---|---|
| M1 Server foundation + auth | Done | 19 server tests; `docker compose up` verified |
| M2 App foundation + auth | Done | 32 app unit tests; emulator journey `journeys/m2-auth.xml` passed |
| M3 Media, posts, profile | Done | 33 server tests, 58 app unit tests, 2 instrumented DAO tests; journey `journeys/m3-posts.xml` passed |
| M4 Follow, feed, search, explore | Done | 37 server tests, 69 app unit tests; journeys `m4-social.xml` + `m4-offline.xml` passed |
| M5 Likes, comments, offline action queue | Done | 40 server tests, 74 app unit tests; journeys `m5-engagement.xml` + `m5-offline-queue.xml` passed |
| M6 Real-time DMs | Done | 42 server tests, 77 app unit tests; journey `m6-dm.xml` passed |
| M7–M8 | Not started | |

Changes from this plan made during M2:
- Kotlin 2.4.20 is applied by putting `kotlin-gradle-plugin` on the root buildscript classpath (AGP 9 built-in Kotlin, per the AGP 9.0 release notes).
- `koin-compose-navigation3` isn't used: single-module app, so entries call `koinViewModel()` inside the plain entryProvider DSL with `rememberViewModelStoreNavEntryDecorator`.
- Main shell uses `NavigationSuiteScaffold` (bottom bar on phones, rail on wide windows).
- Server 401 challenges now send `WWW-Authenticate: Bearer` (RFC 6750) so the Ktor client refreshes tokens.
- Base URL / Google client ID come from a Gradle property or `local.properties` (see `docs/running-the-app.md`).

Changes made during M3:
- Posts are created with `PUT /api/v1/posts/{clientId}` (idempotent) instead of POST; the draft row's id is the post id.
- Uploads are center-cropped to the 4:5…1.91:1 aspect range (server), and the client clamps the same range.
- Room 3 (`androidx.room3`, KSP) with `AndroidSQLiteDriver`; schema export off for now (enable before the first migration in M4).
- Upload drafts are cleared (and their work cancelled) on logout and on server-side session expiry.
- The profile grid uses simple cursor paging in the ViewModel; Paging 3 + RemoteMediator arrives with the feed in M4.
- No expedited work: regular WorkManager work with a network constraint (expedited needs a foreground notification below API 31).

Changes made during M4:
- Follow endpoints are keyed by username (`PUT|DELETE /users/{username}/follow`), not user id.
- Explore is "recent posts from accounts you don't follow"; ranking by likes waits for likes (M5).
- Room schema export is on (`app/schemas`, `androidx.room3` plugin); v1→v2 is an `@AutoMigration` (adds the feed cache).
- Follow/unfollow updates the UI optimistically and reverts on failure; offline queuing of follows comes with the action queue in M5.
- Material 3 Expressive components (e.g. `LoadingIndicator`) aren't public in material3 1.4.0 (BOM 2026.09.00); standard M3 used until a stable release exposes them.

---

## 0. Toolchain & version catalog (do this first)

The template's **Kotlin 2.2.10 is too old**: Coil 3.6.3 needs Kotlin 2.4.x metadata, and
kotlinx-serialization 1.11.0 needs stdlib ≥ 2.3.20. Bump everything together.

| Area | Artifact | Version |
|---|---|---|
| Kotlin (+ compose & serialization plugins) | `org.jetbrains.kotlin.*` | **2.4.20** |
| KSP | `com.google.devtools.ksp` | 2.3.12 |
| AGP | `com.android.application` | 9.4.1 (template 9.3.3 also fine) |
| Compose BOM | `androidx.compose:compose-bom` | 2026.09.00 |
| Navigation 3 | `androidx.navigation3:navigation3-runtime` / `-ui` | 1.2.0 |
| Lifecycle | `lifecycle-runtime-compose`, `-viewmodel-compose`, `-viewmodel-navigation3` | 2.11.0 (template has 2.6.1) |
| Room 3 | `androidx.room3:room3-runtime` / `-compiler` (KSP) / `-paging` / `-testing`, plugin `androidx.room3` | 3.0.3 |
| Paging | `androidx.paging:paging-runtime`, `paging-compose` | 3.5.1 |
| WorkManager | `androidx.work:work-runtime-ktx` | 2.12.0 |
| DataStore | `androidx.datastore:datastore-preferences` | 1.2.1 |
| Credentials | `androidx.credentials:credentials`, `credentials-play-services-auth` | 1.6.0 |
| Google ID | `com.google.android.libraries.identity.googleid:googleid` | 1.2.1 |
| Coil | `io.coil-kt.coil3:coil-compose`, `coil-network-ktor3` | 3.6.3 |
| Koin | `io.insert-koin:koin-bom` → `koin-androidx-compose`, `koin-compose-navigation3`, `koin-ktor` | 4.2.2 |
| Ktor (client + server) | `io.ktor:ktor-bom` | 3.6.0 |
| kotlinx | `kotlinx-serialization-json` / `kotlinx-coroutines-*` | 1.11.0 / 1.11.0 |
| Firebase | `firebase-bom` / plugin `com.google.gms.google-services` | 34.19.0 / 4.5.0 |
| Exposed | `org.jetbrains.exposed:exposed-bom` (core, jdbc, java-time, json) | 1.5.0 — **packages are `org.jetbrains.exposed.v1.*`** |
| Flyway | `flyway-core` + `flyway-database-postgresql` | 13.9.0 (needs JDK 17+) |
| HikariCP / pg JDBC | `com.zaxxer:HikariCP` / `org.postgresql:postgresql` | 7.1.0 / 42.7.13 |
| Testcontainers | `testcontainers-bom` → **`testcontainers-postgresql`** (renamed in 2.x) | 2.0.5 |
| Firebase Admin | `com.google.firebase:firebase-admin` | 9.11.0 |
| Images (server) | `net.coobird:thumbnailator` + `com.twelvemonkeys.imageio:imageio-jpeg` | 0.4.21 / 3.15.2 |
| Password hashing | `com.password4j:password4j` (Argon2id, pure Java) | 1.8.4 |
| Logging | `logback-classic` / `timber` | 1.6.5 / 5.0.1 |
| Testing | `app.cash.turbine:turbine` | 1.2.1 |

**JDK:** 21 LTS for both builds; Docker image `eclipse-temurin:21-jre`.

**Decision (changeable):** use **Room 3**, which is stable, coroutine-only, KSP-only, and modern
enough to be worth showcasing. Fall back to Room 2.8.5 if Room 3 + RemoteMediator examples prove
too thin.

---

## Repo layout

Keep the existing Android project at the root. Add the server as an **independent Gradle build**.

```
Insta/
  app/                         existing Android module (com.android.insta)
  server/                      Ktor server (own settings.gradle.kts, own wrapper)
    src/main/kotlin/com/android/insta/server/
      Application.kt  plugins/  auth/  users/  posts/  feed/  social/  chat/  notifications/  media/  db/
    src/main/resources/db/migration/V1__init.sql ...
    src/main/resources/openapi/documentation.yaml
    Dockerfile
  docker-compose.yml           postgres + server, volumes: pgdata, media
  .env.example                 JWT secret, DB creds, FIREBASE_CREDENTIALS path
  .github/workflows/ci.yml
  docs/
```

Android packages (single module), organized by feature:
`core/{network,database,datastore,sync,designsystem,util}`, `feature/{auth,feed,post,profile,search,
explore,chat,notifications,settings}`, `navigation/`, `di/`. Each feature has `data/` (repository,
DTO↔entity mappers), `ui/` (Screen, ViewModel, UiState, events).

---

## Milestones

Each milestone ends **demoable and tested**. Server work comes before the matching app work.

### M1 — Server foundation + auth
- Ktor app (Netty), plugins: ContentNegotiation (kotlinx), StatusPages (error envelope
  `{error:{code,message,details}}`), CallLogging + CallId, CORS off, RateLimit (auth, writes),
  Authentication (JWT), WebSockets, OpenAPI/Swagger at `/docs` from a static YAML.
- DB: HikariCP → Flyway migrate on startup → Exposed. `V1__init.sql` contains the full spec schema
  (users, refresh_tokens, media, posts, follows, likes, comments, conversations, messages,
  conversation_reads, notifications, device_tokens) + `pg_trgm` extension + indexes.
- Auth endpoints: `POST /auth/register`, `/auth/login`, `/auth/google` (verify ID token against
  Google JWKS, `aud` = web client ID; link by email or create), `/auth/refresh` (rotate; reuse of a
  revoked token → revoke the whole family), `/auth/logout`. Access JWT 15 min, refresh 30 days
  (hashed in DB). Argon2id via password4j.
- Koin in Ktor (`koin-ktor`) for repositories/services.
- docker-compose: `postgres:17` with healthcheck, server depends_on healthy.
- Tests: `testApplication` + Testcontainers Postgres base class; register/login/refresh/reuse cases.

### M2 — App foundation + auth
- Toolchain bump (§0); plugins: ksp, room3, serialization, google-services.
- `InstaApp` with Koin; Ktor `HttpClient` (OkHttp engine) with ContentNegotiation, Auth plugin
  (bearer + `refreshTokens{}` calling `/auth/refresh`), logging, base URL per build type
  (`http://10.0.2.2:8080` debug via BuildConfig; cleartext allowed only in debug network config).
- Token storage: DataStore (refresh token encrypted with an Android Keystore AES key).
- Navigation 3: `NavDisplay` + `entryProvider { entry<Route.X> { ... } }`, `@Serializable` routes,
  separate back stacks for logged-out/logged-in, bottom nav (Feed, Explore, Create, Notifications,
  Profile) with one back stack per tab, `koinViewModel()` per entry
  (`rememberViewModelStoreNavEntryDecorator`).
- Screens: Login, Register, Google button (Credential Manager `GetGoogleIdOption`), Splash/session gate.
- Tests: AuthViewModel (Turbine, fake repo); one Compose UI test for login validation.

### M3 — Media, posts, profile
- Server: `POST /media` (multipart, ≤10 MB, JPEG/PNG/WebP via magic bytes) → Thumbnailator:
  apply EXIF orientation, re-encode JPEG (drops all metadata), 1080px + 320px → `MediaStorage`
  (`LocalDiskMediaStorage`, root `/data/media`) → `GET /media/{id}/{full|thumb}` with long
  Cache-Control + ETag. `POST/GET/DELETE /posts`, `GET /users/{username}`, `PATCH /me`,
  `GET /users/{id}/posts` (cursor).
- App: Photo Picker → client downscale/compress → **draft row in Room** → `PostUploadWorker`
  (expedited, network constraint, exponential backoff; upload media then create post with a client
  UUID → idempotent). "Posting…" banner observes WorkInfo. Profile screen (header + 3-col grid with
  thumbs), Edit Profile (avatar reuses pipeline). Coil 3 with `coil-network-ktor3` sharing the
  authed HttpClient, disk cache 250 MB.

### M4 — Follow, feed, search, explore
- Server: `PUT/DELETE /users/{id}/follow` (idempotent), followers/following lists, `GET /feed`
  (keyset on `(created_at, id)`, posts from followees ∪ self; include `liked_by_me`, counts, author),
  `GET /search/users?q=` (pg_trgm similarity + prefix on username/display_name),
  `GET /explore` (non-followed authors; like count over the last 7 days, then recency).
- App: Paging 3 `RemoteMediator` for feed + profile grid, writing to Room (`feed_items`,
  `remote_keys`). **Network-first:** refresh on open and pull-to-refresh; when offline, show Room
  pages + an offline banner. Empty feed → "Find people" CTA → Explore. Search with debounce (300 ms).

### M5 — Likes, comments, offline action queue
- Server: `PUT/DELETE /posts/{id}/like`; `PUT /posts/{id}/comments/{clientId}` (idempotent create),
  `GET` comments (cursor), `DELETE` own comment. Counters are updated in the same transaction.
- App **action queue** (`pending_actions` table: id UUID, type, target_id, payload JSON, created_at,
  attempts, state). `ActionQueue.enqueue()` applies an **optimistic local update** to Room, then
  schedules a unique `SyncWorker` (APPEND_OR_REPLACE, network constraint) that drains FIFO.
  Rules:
  - Collapse opposites on the same target (like→unlike, follow→unfollow) before sending.
  - 4xx (except 408/429) → mark failed, revert the optimistic state, surface a snackbar; 5xx/IO →
    retry with backoff.
  - Merging on network refresh: pending actions are re-applied over fresh server rows, so the UI
    never "flickers back".
- Comments sheet shows pending/failed comments with tap-to-retry.

### M6 — Real-time DMs
- Server: `GET /conversations` (with unread count + last message), `POST /conversations` (get or
  create by peer), `GET /conversations/{id}/messages` (cursor), `PUT .../messages/{clientId}`
  (idempotent), `POST .../read`. WebSocket `/ws` (JWT in the header): a `ConnectionRegistry`
  (userId → sessions) pushes `message.new`, `message.read`, `notification.new`, `badge`.
  JSON frames with `type` discriminator (sealed class + kotlinx).
- App: `RealtimeClient` (Ktor client WebSockets) bound to process lifecycle
  (`ProcessLifecycleOwner`): connect when foregrounded and logged in; reconnect with jittered
  backoff; re-auth on 401. Incoming events → Room → UI. Sending = queue action (M5) with optimistic
  "sending…" bubble; WS ack or REST response flips it to sent. Inbox + Thread screens, seen receipt.

### M7 — Notifications + FCM
- Server: a notifications row is written in the same transaction as like/comment/follow/message
  (no self-notifications; collapse repeated likes). `GET /notifications` (cursor),
  `POST /notifications/read`, `PUT /me/devices/{token}`. A `PushSender` interface:
  `FcmPushSender` (Firebase Admin, credentials from a mounted file) sends only when the user has no
  live WS session; `NoopPushSender` when no credentials are configured, so the stack runs without
  Firebase.
- App: `FirebaseMessagingService` (token upload on refresh/login), notification channels, POST_NOTIFICATIONS
  runtime permission (API 33+) requested contextually, deep links (`insta://post/{id}`,
  `insta://chat/{id}`, `insta://user/{username}`) mapped to Nav3 back stacks. Notifications tab +
  badge from WS.

### M8 — Account deletion, hardening, showcase
- `DELETE /me` (re-auth required; cascades; deletes media files after commit; revokes tokens).
  Settings screen: logout, delete account.
- Seed script (`server/seed`) creating demo users/posts/follows/chats so a reviewer sees a living app.
- Accessibility pass (content descriptions, 48dp, font scaling), empty/error/loading states everywhere,
  dark theme.
- CI (`.github/workflows/ci.yml`): job `server` (JDK 21, `./gradlew test` with Testcontainers on
  ubuntu runner), job `android` (`./gradlew lint testDebugUnitTest assembleDebug`), ktlint + detekt.
  A dummy `google-services.json` is generated in CI.
- README: architecture diagram, offline-queue explainer, `docker compose up` quickstart, Firebase
  setup steps, screenshots/GIF.

---

## Secrets & setup checklist (you)
- Google Cloud: OAuth **Web** client ID (used as `serverClientId` + server `aud`) and an Android
  client with the debug keystore SHA-1.
- Firebase project: `app/google-services.json` (gitignored) and a service-account JSON mounted into
  the server container (gitignored; path in `.env`).
- `.env` from `.env.example` (JWT secret ≥ 256-bit, DB password).
- Recommend `git init` before M1, with a `.gitignore` covering the secrets above.

## Verification (end of each milestone)
1. `docker compose up --build` → `GET /health` OK, Swagger at `http://localhost:8080/docs`.
2. `cd server && ./gradlew test` green (Testcontainers needs Docker running).
3. `./gradlew testDebugUnitTest connectedDebugAndroidTest` green.
4. Manual on emulator: the milestone's flow end to end, including **airplane-mode** checks from M4
   onward (cached feed visible; like/comment/follow/DM queued, then synced after reconnect).

## Remaining open items
- Room 3 vs Room 2 (default: Room 3).
- Explore "popular" window (default 7 days).
- Whether to add a KMP shared-DTO module later (currently duplicated DTOs).

Changes made during M5:
- The offline action queue covers likes/unlikes and comments; follows stay direct and optimistic (revert on failure). DMs join the queue in M6.
- Like/unlike for the same post collapse to the latest choice before sending (the endpoints are idempotent, so only the final state matters).
- Comment lists aren't cached offline yet; only queued comments show while offline.
- Explore ranking by likes is still deferred (keyset paging over a score needs a separate design).
- DB v3 (`pending_actions`, `feed_posts.likedByMe`) via `@AutoMigration(2, 3)`, verified by upgrading the installed app on the emulator.

Changes made during M6:
- Threads are opened by peer username (`POST /conversations {username}` get-or-create); the inbox is reached from a Messages action in the Home top bar.
- DMs reuse the M5 offline action queue (type `message`, idempotent `PUT /conversations/{id}/messages/{clientId}`).
- The WebSocket is open only while signed in **and** foregrounded; background delivery is M7's FCM.
- Message history loads the latest page (50); older pages and unread badges on the tab bar are not implemented yet.
