# Insta — v2 Implementation Plan

Scope: `docs/SPEC.md` (v2, approved 2026-10-06).
**In:** Redis infrastructure (job queue, rate limits, cache), carousels, video + HLS + Reels tab, photo stories,
private accounts / block / mute, saved posts, comment replies + likes, hashtags + mentions, group DMs (≤ 16) and
sharing posts into DMs, offline support for the new actions.
**Out:** reporting/moderation, ranked explore, video stories, story replies, highlights, video trim, KMP/iOS, cloud
hosting, real SMS provider, group admin roles.

v1 (M1–M10) is complete; its plan is archived in `docs/v1/IMPLEMENTATION_PLAN.md`. Milestone numbering continues.
Versions were checked against Google Maven, Maven Central and Docker Hub on **2026-10-06**.

## Status

| Milestone | State | Notes |
|---|---|---|
| M11 Server: Redis infrastructure (queue, rate limits, cache) | Done | 65 server tests (+14: 11 job runner, 3 Redis features); live check on Docker: health up/degraded, OTP 503 with Redis stopped, cache fail-open, a job made due during the outage ran ~33 s after Redis returned |
| M12 Server: carousels (multi-media posts) | Done | 70 server tests (+5: 4 carousel routes, 1 V6 migration); live check on Docker: seeded 18 posts / 30 items, a 3-item carousel served at 1080x1350 per item, delete removed its 3 items |
| M13 App: carousels | Done | 98 app unit tests (+9), 6 instrumented (+3: two Room 3→4 migration tests, draft items DAO); lint 0 errors; journey `m13-carousel.xml` 12/12 incl. offline share → force-stop → resume |
| M14 Server: video upload, FFmpeg/HLS, reels | Not started | |
| M15 App: video, Reels tab, navigation change | Not started | |
| M16 Server: stories | Not started | |
| M17 App: stories | Not started | |
| M18 Server: private accounts, block, mute, media access control | Not started | |
| M19 App: privacy & safety | Not started | |
| M20 Server: saved posts, replies, comment likes, hashtags, mentions | Not started | |
| M21 App: saved, replies, hashtags, mentions | Not started | |
| M22 Server: group DMs + shared posts | Not started | |
| M23 App: group DMs + share sheet | Not started | |

---

## Toolchain & version catalog

Everything already in `gradle/libs.versions.toml` and `server/gradle/libs.versions.toml` stays as is (Kotlin 2.4.20,
AGP 9.3.3, Compose BOM 2026.09.00, Ktor 3.6.0, Koin 4.2.2, Room 3.0.3, Exposed 1.5.0, Flyway 13.9.0, Testcontainers
2.0.5, …). Locked items (Kotlin, AGP, Compose BOM, KSP, SDK levels) are **not** changed. New entries only:

| Area | Artifact | Version |
|---|---|---|
| Video playback (app) | `androidx.media3:media3-exoplayer`, `media3-exoplayer-hls` | 1.11.1 |
| Video compression (app) | `androidx.media3:media3-transformer`, `media3-effect` | 1.11.1 |
| Player UI (app) | `androidx.media3:media3-ui-compose` (`PlayerSurface`; controls are our own M3) | 1.11.1 |
| Redis client (server) | `io.lettuce:lettuce-core` | 7.8.0.RELEASE |
| Lettuce coroutines bridge (server) | `org.jetbrains.kotlinx:kotlinx-coroutines-reactive` | 1.11.0 (matches coroutines) |
| Redis (compose + tests) | Docker image `redis` | 8.8-alpine (8.8.3) |
| Redis in tests | Testcontainers `GenericContainer("redis:8.8-alpine")` — no extra module | 2.0.5 (existing BOM) |
| FFmpeg (server image + CI) | distro package in `eclipse-temurin:21-jre` (Ubuntu 24.04) | 6.1.x |
| Pager, link text (app) | `HorizontalPager` / `VerticalPager`, `LinkAnnotation` — compose-foundation from the BOM | BOM 2026.09.00 |

---

## Repo / module layout

Unchanged shape: single Android module `app/` + independent server build `server/`. New packages:

```
server/src/main/kotlin/com/android/insta/server/
  redis/          RedisModule (Lettuce client, coroutines commands), RedisCache, RedisRateLimiter (Lua)
  jobs/           Job, JobRepository (Postgres `jobs`), JobQueue (Redis Streams), JobDispatcher, JobWorkers, Reconciler
  media/          + uploads/ (UploadSessions, UploadRoutes), video/ (VideoTranscoder, FfmpegTranscoder, HlsRoutes)
  posts/          + PostMedia, reels routes
  stories/        StoryRoutes, StoryService, StoryRepository
  privacy/        Visibility (central rule), FollowRequests, Blocks, Mutes
  social/         + saved, hashtags, mentions (TextParser)
  chat/           + groups (members, system messages), shared posts
server/src/main/resources/db/migration/V5__… onward
docker-compose.yml   + redis service (AOF volume, healthcheck)

app/src/main/java/com/android/insta/
  core/media/     + video/ (VideoCompressor via Transformer, PlayerPool, AuthDataSource), ChunkedUploader
  core/ui/        + MediaPager, LinkifiedText, VideoPlayer
  feature/reels/  ReelsScreen, ReelsViewModel, data/
  feature/stories/ StoryTray, StoryViewer, StoryViewModel, data/
  feature/privacy/ BlockedAccounts, FollowRequests
  feature/hashtag/ HashtagScreen
  feature/chat/   + group/ (NewGroup, GroupInfo), share/ (ShareSheet)
```

Room DB goes v3 → v4 (M13) → v5 (M15) → v6 (M17) → v7 (M21) → v8 (M23): auto-migrations where possible, each with a
`MigrationTestHelper` instrumented test.

---

## Milestones

Every app milestone that adds a new screen flow starts with one **android-kmp-architect** pass (M13, M15, M17, M19,
M21, M23) and ends with one **compose-reviewer** pass on `git diff master...HEAD`. Server milestones stay
backward compatible (additive DTO fields), so each can merge on its own before its app milestone.

### M11 — Server: Redis infrastructure
- **Compose:** `redis:8.8-alpine` with `appendonly yes`, volume `redisdata`, healthcheck; server `depends_on` it;
  `REDIS_URL` in config and `.env.example`.
- **RedisModule:** one Lettuce `RedisClient` + connection (Koin singletons), coroutines API, command timeout 1 s,
  closed on `ApplicationStopping`.
- **Job system** (transactional outbox):
  - `V5__jobs.sql`: `jobs(id uuid v7, type, payload jsonb, status queued|running|done|dead, attempts, run_at,
    last_error, created_at, updated_at)`.
  - Services insert job rows **in the same transaction** as their data; after commit the `JobDispatcher` XADDs due
    jobs to `insta:jobs:{type}`.
  - Workers: XREADGROUP (group `workers`), handler per type, XACK on success; failure → `attempts+1`, exponential
    `run_at` backoff, after 3 attempts `dead` + XADD to `insta:jobs:dlq`. XAUTOCLAIM picks up messages idle > 5 min.
  - **Reconciler** every 30 s re-dispatches `queued` rows whose `run_at` has passed (covers delayed jobs, Redis
    restarts and a down Redis).
  - Concurrency per type from config (transcode = 2 later). A `noop` job type proves the pipeline in tests.
- **Rate limiting:** `RedisRateLimiter` (fixed-window counter via a Lua script) behind Ktor's `RateLimit` plugin
  (custom provider) for per-IP limits; OTP cooldown / hourly cap move from the current store to Redis keys
  `rl:otp:{e164}:*`. Redis error → general limits allow, OTP limits refuse (`503 OTP_UNAVAILABLE`).
- **Cache:** `Cache.getOrLoad(key, ttl, serializer)` + `invalidate(keys)`; keys `insta:v1:{entity}:{id}`; errors
  log and fall through to Postgres. First users: public profile + counters (invalidated on follow/post changes).
- **Health:** `/health` reports `redis: up|down` (status stays 200 when only Redis is down, `degraded=true`).
- **Tests** (Testcontainers Postgres + Redis): outbox commit/rollback, retry → DLQ, XAUTOCLAIM recovery, reconciler
  after Redis restart, rate limiter windows, OTP fail-closed / API fail-open with Redis stopped, cache invalidation.
- **Demo / live check:** `docker compose up --build`, `/health` shows Redis; `docker compose stop redis` → API keeps
  working, OTP send returns 503; restart → `noop` job queued meanwhile completes. No app change, no emulator journey.

### M12 — Server: carousels
- `V6__carousels.sql`: `media` gains `type`, `width`, `height`, `duration_ms`, `status` (existing rows `photo`,
  `ready`); `post_media(post_id, position, media_id, PK(post_id, position))` filled from `posts.media_id`, then
  `posts.media_id` dropped; `posts.kind` (`post` default) and `posts.status` (`published` default).
- `PUT /posts/{clientId}` accepts `mediaIds: [1..10]` (still accepts the old single `mediaId`); all media owned by
  the caller, unused, and photo-only until M14. The aspect ratio comes from the first item; later items are center
  cropped to it on the server.
- `PostDto` gains `media: [{id, type, url, thumbUrl, width, height}]`; existing `mediaUrl` / `thumbUrl` stay (first
  item) so the v1 app keeps working.
- Deleting a post deletes every media file after commit.
- Tests: create 1/10/11 items, foreign media rejected, order kept, migration keeps old posts, delete cleans files.
  OpenAPI updated.

### M13 — App: carousels
- Create: `PickMultipleVisualMedia(maxItems = 10)` (images only for now); horizontal strip to reorder/remove; a
  single crop-ratio choice applied to every item.
- Drafts: Room v4 `draft_items(draft_id, position, local_uri, uploaded_media_id)`; the upload worker uploads items
  one by one (resumes from the first item without a media id), then creates the post with all ids.
- Feed / post detail: `MediaPager` (`HorizontalPager` + dot indicator + "1/5" chip), double-tap like still works;
  profile grid shows a carousel badge.
- Tests: draft repository (resume after partial upload), ViewModel for picker limits/reorder, DTO mapping; Room
  migration v3→v4 instrumented test. Journey `journeys/m13-carousel.xml` (post 3 photos, swipe in feed, kill the
  app mid-upload → resumes).

### M14 — Server: video upload, FFmpeg / HLS, reels
- **Resumable upload:** `POST /uploads {size, mimeType}` → `{id, chunkSize}`; `PUT /uploads/{id}` with
  `Content-Range` (must continue at the current offset, idempotent re-send of the last chunk); `HEAD /uploads/{id}` →
  `Upload-Offset`; `POST /uploads/{id}/complete {kind}` → media row `processing` + `transcode` job. `V7__video.sql`
  adds `upload_sessions` and poster/HLS columns. Limits: `video/mp4`, ≤ 150 MB. A cleanup job expires sessions
  after 24 h.
- **Transcoding:** `VideoTranscoder` interface. `FfmpegTranscoder` runs `ffprobe` (duration ≤ 45.5 s, has a video
  stream, rotation) and then one `ffmpeg` run producing HLS fMP4, 6 s segments, 1080p/720p/360p + `master.m3u8` +
  poster JPEG, into `media/{id}/hls/`. The process has a timeout (3 min), is killed on cancellation, and its working
  dir is removed on failure. 2 concurrent transcode workers.
- **Posts:** media may be `video` once `ready`. A post whose media isn't ready yet is created with
  `status=processing`; when the last media is ready the job flips it to `published` (and it appears in feeds).
  After the final failure: `failed` + an in-app `media_failed` notification to the author.
- **Reels:** `kind=reel` requires exactly one vertical video (9:16…4:5). `GET /reels?cursor` (newest published
  reels, keyset paging), `GET /users/{username}/reels`. The home feed and explore exclude reels; profile posts
  exclude reels.
- **Serving:** `GET /media/{id}/hls/{file}` with a strict file-name whitelist (`master.m3u8`, `{rendition}/…`),
  correct content types, `Cache-Control: immutable` for segments. Still public until M18.
- **Docker / CI:** `apt-get install -y --no-install-recommends ffmpeg` in the runtime stage; CI installs ffmpeg for
  server tests.
- **Tests:** chunk protocol (out-of-order chunk 409, resume, re-send last chunk, size limit), job state machine with
  a fake transcoder (ready / retry / dead → post failed + notification), reels validation and paging. Real-FFmpeg
  test (small generated clip via `ffmpeg -f lavfi`) runs only when `ffmpeg` is on PATH (`assumeTrue`), always in CI.

### M15 — App: video, Reels tab, navigation change
- **Navigation:** tabs Feed · Explore · Create · Reels · Profile. Activity moves to a heart `BadgedBox` in the Home
  top bar next to Messages; `insta://activity` and push taps still open it.
- **Create:** picker allows video. Reel mode (single vertical video) or post mode (carousel with videos). Duration
  > 45 s is rejected up front.
- **Compression:** `VideoCompressor` (Media3 Transformer: H.264 1080p max, AAC, `Presentation` effect for crop to the
  post ratio) inside the upload `CoroutineWorker` running as foreground (`dataSync` type, progress notification).
- **ChunkedUploader:** 5 MB chunks, resumes with `HEAD` offset after process death; stored in the draft
  (Room v5: `draft_items.type`, `duration_ms`, `compressed_path`, `upload_session_id`). Upload progress UI shows
  "Compressing… / Uploading 40% / Processing…".
- **Playback:** `PlayerPool` (max 3 ExoPlayer instances, released in `onStop`), HLS via `HlsMediaSource`; poster
  shown until first frame; `VideoPlayer` composable with `PlayerSurface`, mute toggle, tap to pause.
  - **Feed:** carousel videos autoplay muted only for the most visible item (≥ 60 %).
  - **Reels tab:** `VerticalPager`, one playing page, next page preloaded, like/comment/profile overlay reusing
    the engagement layer, plays on loop.
- **Profile:** tabs Posts | Reels (grid of posters with play icon).
- **Tests:** ReelsViewModel (paging, current page), upload state machine (compress → upload → complete with
  resume), Room v4→v5 migration test, PlayerPool unit test (assignment/release logic behind an interface).
  Journey `journeys/m15-reels.xml`: upload a reel (sample clip pushed via `adb push`), wait for processing, swipe
  reels, Activity via the heart icon.

### M16 — Server: stories
- `V8__stories.sql`: `stories(id, author_id, media_id, created_at, expires_at)`, `story_views(story_id, viewer_id,
  viewed_at, PK)`.
- `POST /stories {mediaId}` (photo, ready, 9:16 crop on the server) → enqueues a delayed `story_expire` job at
  `expires_at` (deletes rows + files). Every read filters `expires_at > now()`.
- `GET /stories/tray` → users you follow + yourself with active stories, `hasUnseen`, unseen first then newest;
  cached per viewer (`insta:v1:tray:{userId}`, TTL 60 s; own key invalidated on post/view).
- `GET /users/{username}/stories`, `PUT /stories/{id}/view` (idempotent), `GET /stories/{id}/viewers` (author only,
  paged), `DELETE /stories/{id}`.
- Tests: expiry filter with a test clock, expire job deletes files, tray order + seen state, viewers author-only,
  view idempotency, cache invalidation.

### M17 — App: stories
- **Tray** (`LazyRow`) on top of the feed: "Your story" with an add badge, gradient ring for unseen, grey for seen;
  cached in Room v6 (`story_tray`) for offline display.
- **Add story:** photo picker → 9:16 crop preview → WorkManager upload (reuses the draft/upload pipeline).
- **Viewer** (full-screen Nav3 entry): `HorizontalPager` across users, segmented progress bar (5 s per story),
  tap left/right, hold to pause, swipe down to close, images prefetched with Coil. Views are sent through the
  action queue (`story_view`). The author sees "Seen by N" → viewers bottom sheet; delete from the viewer.
- **Tests:** StoryViewerViewModel (timer, next/prev across users, pause), tray repository (offline fallback), queue
  type, Room v5→v6 migration. Journey `journeys/m17-stories.xml` (post a story as A, view as B, A sees B in viewers).

### M18 — Server: private accounts, block, mute, media access control
- `V9__privacy.sql`: `users.is_private`, `follow_requests(requester, target, created_at)`, `blocks(blocker,
  blocked)`, `mutes(muter, muted, posts bool, stories bool)`.
- **Central rule** `Visibility`: `canSeeContent(viewer, author)` = not blocked either way **and** (author public
  **or** viewer follows **or** self); `canSeeProfile` = not blocked either way. Exposed as one SQL predicate
  helper used by every query, plus a Kotlin check for single-item endpoints.
- **Applied to:** profile posts/reels/stories, post detail, feed, explore, search, reels, story tray, hashtag pages
  (M20), comments and likes lists (hide blocked users), notifications (no notifications between blocked users),
  1:1 DMs (`403 BLOCKED`), and **media GETs**, which now require auth and check visibility (result cached
  `insta:v1:vis:{viewer}:{media}`, 60 s; avatars allowed except across a block).
- **Follow:** following a private account creates a request (`202 {state: "requested"}`), `DELETE` cancels it.
  `GET /me/follow-requests`, `POST /me/follow-requests/{username}/approve|decline`. Notifications
  `follow_request` / `follow_accepted`. `PATCH /me {isPrivate:false}` auto-approves pending requests.
- **Block:** `PUT|DELETE /users/{username}/block`, `GET /me/blocked`; blocking removes follows + requests both ways
  in one transaction and invalidates caches. **Mute:** `PUT /users/{username}/mute {posts, stories}`,
  `DELETE` → feed, tray and reels filter muted users.
- `UserDto` gains `isPrivate`, `followState` (none/requested/following), `isBlocked`, `isMuted`.
- **Tests:** an endpoint × relationship matrix (self, follower, stranger→private, stranger→public, blocker,
  blocked, muted) asserting visible/hidden/403 for every content endpoint and for media GETs; request lifecycle;
  block side effects; auto-approve.

### M19 — App: privacy & safety
- Settings: "Private account" switch; "Blocked accounts" list with unblock.
- Profile: lock placeholder ("This account is private") for non-followers, Follow / Requested / Following button
  states, overflow menu: Block / Unblock (confirm dialog), Mute (posts / stories switches in a sheet).
- Activity: "Follow requests" row → list with Confirm / Delete.
- **Authenticated media:** Coil's network fetcher uses the authenticated Ktor client; ExoPlayer uses a
  `ResolvingDataSource` that adds the current bearer token (refreshes on 401). A 403/404 on content shows
  "Content unavailable".
- Blocking purges that user's posts/stories from Room caches and closes an open 1:1 thread.
- Tests: ProfileViewModel follow-state transitions (incl. revert on failure), follow requests VM, blocked list,
  cache purge in repositories. Journey `journeys/m19-privacy.xml` (make A private, B requests, A approves, B sees
  posts; A blocks B → B can't find A).

### M20 — Server: saved posts, replies, comment likes, hashtags, mentions
- `V10__social.sql`: `saved_posts(user_id, post_id, created_at)`, `comments.parent_id` + `reply_count`,
  `comment_likes` + `comments.like_count`, `hashtags(id, tag unique)`, `post_hashtags`, `mentions(source_type,
  source_id, user_id)`.
- `PUT|DELETE /posts/{id}/save`, `GET /me/saved` (only yours; respects visibility at read time).
- Replies: `PUT /posts/{id}/comments/{clientId}` gains `parentId` (must be a top-level comment of the same post);
  `GET /comments/{id}/replies?cursor`. `PUT|DELETE /comments/{id}/like`. Deleting a parent deletes its replies.
- `TextParser`: `#tag` (Unicode letters/digits/_, ≤ 30 per text, lower-cased) and `@username` (existing username
  rules); run on caption/comment create. `GET /tags/{tag}/posts?cursor` (chronological, visibility-filtered),
  `GET /tags/search?q=`.
- Notifications `reply`, `comment_like`, `mention` (only if the target can see the content and isn't blocked).
- Tests: parser cases, reply depth rule, counters, mention visibility, saved visibility after the author goes
  private, tag paging.

### M21 — App: saved, replies, hashtags, mentions
- Save toggle on posts and reels (action queue `save`/`unsave`); own profile gets a Saved tab (only visible to you).
- Comments: "View N replies" expanders, Reply sets the composer to reply mode with `@username ` prefilled; heart on
  comments (queue `comment_like`); replies queued offline like comments.
- `LinkifiedText` (`AnnotatedString` + `LinkAnnotation.Clickable`) for captions/comments: `#tag` → Hashtag screen,
  `@user` → profile. Search gets Accounts | Tags tabs.
- Room v7: cached saved flag, reply counts. Tests: comments VM (reply mode, expand, optimistic counts), text
  linkifier, queue types, migration test. Journey `journeys/m21-social.xml`.

### M22 — Server: group DMs + shared posts
- `V11__groups.sql`: `conversations.kind`, `name`, `created_by`; `conversation_members(conversation_id, user_id,
  role, joined_at, left_at, last_read_message_id)` filled from existing pairs and `conversation_reads`;
  `messages.kind` (text/shared_post/system) + `shared_post_id`. A unique index keeps one `direct` conversation per
  pair.
- `POST /conversations/groups {name, usernames}` (2–15 others, total ≤ 16), `PATCH /conversations/{id} {name}`
  (creator), `POST /conversations/{id}/members {usernames}` (any member, ≤ 16), `DELETE
  /conversations/{id}/members/{username}` (self = leave, creator = remove). System messages for
  create/add/leave/remove/rename.
- Messages: `PUT /conversations/{id}/messages/{clientId}` accepts `sharedPostId`; the payload carries a post preview
  only if the **reader** can see the post, else `unavailable`. Allowed for any member; 1:1 blocked → `403 BLOCKED`.
- Inbox and unread counts per member; WebSocket and FCM fan out to active members; `group_added` notification.
  `ConversationDto` gains `kind`, `name`, `members`, `hasBlockedMember` (for the warning).
- Tests: member limits/roles, leave/remove, system messages, fan-out, unread per member, shared-post visibility per
  reader, migration of existing 1:1 threads.

### M23 — App: group DMs + share sheet
- Inbox shows groups (stacked avatars, name). "New group": multi-select user search (≤ 15), name → create.
- Thread: sender name + avatar on group messages, system message rows, shared-post bubble (tap → post/reel,
  "unavailable" state), blocked-member warning banner.
- Group info screen: rename (creator), members list, add members, remove (creator), leave.
- Share sheet from post/reel overflow: recent conversations + search, multi-select send (queued offline as
  `message` with `sharedPostId`).
- Deep link `insta://conversation/{id}` (username links still work for 1:1). Room v8: conversation members cache.
- Tests: group creation VM, group info VM permissions, share sheet send, queue payload, migration test.
  Journey `journeys/m23-groups.xml` (A creates a group with B and C, shares a reel, C leaves).

---

## Secrets & setup checklist (you)
- Nothing new is required for M11–M13: `docker compose up --build` pulls Redis.
- **M14+:** optional `ffmpeg` on the host (`winget install Gyan.FFmpeg`) to run the real-FFmpeg server tests
  locally; without it they're skipped locally and run in CI.
- **M15+:** sample vertical clips (≤ 45 s, MP4) on the emulator for journeys: `adb push clip.mp4
  /sdcard/Movies/`. Give the emulator ≥ 4 GB RAM and enough disk for Transformer output.
- Expect the server image to grow by roughly 100–150 MB with FFmpeg; `docker volume rm insta_media` resets media.
- Existing secrets (Google OAuth client IDs, Firebase files, `.env`) are unchanged; add `REDIS_URL` to `.env` from
  `.env.example` in M11.

## Verification (end of each milestone)
1. `docker compose up --build` → `/health` OK (with `redis: up` from M11), Swagger at `/docs` updated.
2. `cd server && ./gradlew test` green (Testcontainers Postgres + Redis; FFmpeg tests in CI).
3. App: `./gradlew testDebugUnitTest lintDebug` green, lint 0 errors; `connectedDebugAndroidTest` when Room or UI
   changed (every app milestone has a migration test).
4. Emulator journey `journeys/mX-*.xml` + results with screenshots, including an airplane-mode step for every new
   queued action.
5. compose-reviewer on the branch diff; BLOCKER/MAJOR fixed.
6. Status row + "Changes made during Mx" updated here; commit on the task branch, merge to master.

## Changes made during M11
- **Rate limiting** uses a small route-scoped plugin (`Route.rateLimited` / `authRateLimited`) instead of a custom
  provider for Ktor's `RateLimit`; the `ktor-server-rate-limit` dependency is gone. 429s now carry `Retry-After`.
- **OTP limits** are a sliding log per number (sorted set + Lua) using the injected `Clock`, not Redis TTLs, so the
  existing `MutableClock` tests of the cooldown and hourly cap still apply unchanged. The Postgres look-up of recent
  codes was removed.
- **XAUTOCLAIM** only acknowledges entries left pending by dead consumers; re-running their jobs is the Postgres
  side's job (`requeueStale` + re-dispatch). That keeps one path for "run this again".
- **Recurring jobs** (`JobRegistration.every`) and a `dedupe_key` (partial unique index on active rows) were added.
  The first one is an hourly `maintenance` job (expired OTP codes > 1 day, finished jobs > 7 days), which replaces
  the planned `noop` type for the live check.
- **Cache** covers profile counters only (posts / followers / following, 5 min TTL), keyed by user id. The user row
  itself is a cheap indexed read and isn't cached. Invalidated by follow/unfollow (both users), post create/delete
  and account deletion (the user and everyone they followed or were followed by).
- **Bug found in the live check:** a fixed client-wide Lettuce timeout (`TimeoutOptions.enabled(1s)`) overrode the
  longer timeout of the blocking stream connections, so `XREADGROUP BLOCK 2s` timed out and an entry delivered
  during an abandoned read waited for the reconciler (~1 min). Timeouts now come from each connection's `RedisURI`;
  covered by `a worker's blocking read outlasts the regular command timeout` (fails on the old setting).
- The runner stops on `ApplicationStopPreparing`, before Koin closes Redis, so shutdown is quiet.
- No app change and no emulator journey (server only); compose-reviewer skipped for the same reason.
- Compose: `redis:8.8-alpine` with AOF and a `redisdata` volume, `REDIS_URL` set for the server; `.env.example`
  documents `REDIS_URL` for running the server outside Docker.

## Changes made during M12
- `PostDto` keeps `imageUrl` / `thumbUrl` / `width` / `height` (the plan called them `mediaUrl` / `thumbUrl`) for the
  cover, and adds `media: [{id, type, url, thumbUrl, width, height}]`. The app already decodes with
  `ignoreUnknownKeys`, so the v1 app keeps working against this server.
- `CreatePostRequest(mediaId, caption, mediaIds)`: `caption` stays the second field so existing callers compile;
  sending both `mediaId` and `mediaIds` is a 400.
- **Cropping:** items 2…n are center-cropped to the cover's ratio (1 % tolerance) into a **new** file
  (`{id}_full_{w}x{h}.jpg`) before the transaction; the row is repointed inside it and the old file deleted after
  commit (new files deleted on any failure, and on an idempotent retry). Media ETags now come from the file name, so a
  re-cropped image gets a new tag. Thumbnails stay 320 px squares.
- `post_media.media_id` is `ON DELETE CASCADE`: with a plain reference, deleting a user failed because Postgres
  checked it before the posts cascade had removed the rows. Covered by `CarouselMigrationTest`, which also checks
  that V6 turns an existing post into a one-item carousel.
- Media gains `type` / `status` and posts `kind` / `status` (all defaults: photo, ready, post, published). Videos and
  not-ready media are rejected for now (`INVALID_MEDIA`, `MEDIA_NOT_READY`) until M14.
- Notifications take the post thumbnail from the cover (`post_media.position = 0`).
- Demo seed: each account's first post is a 3-photo carousel (18 posts, 30 photos).

## Changes made during M13
- **Room 3 → 4 is a hand-written `Migration`**, not an auto-migration: each draft's photo moves into `draft_items`
  and `post_drafts` drops `imagePath` / `mediaId` (rows move between tables). Covered by `AppDatabaseMigrationTest`
  (`room3-testing` 3.0.3 added for `MigrationTestHelper`). In Room 3 converters are `@ColumnTypeConverter(s)` and
  `migrate` is `suspend`.
- The feed cache keeps the carousel as a JSON `media` column (`MediaListConverter`) instead of a child table: items
  are always read and written with their post. Old cached rows get `[]` and fall back to the cover fields
  (`Post.items`).
- **Cropping happens on the device** when compressing (`CropAspect`: Original, 1:1, 4:5, 1.91:1). With Original the
  server still crops items 2…n to the cover (M12). The preview shows Original fitted in a square.
- **Reordering** uses "Move earlier / Move later / Remove" on the selected thumbnail instead of drag and drop: simple,
  accessible (each thumbnail is a selectable tab announcing "Photo n of m"), and testable in the ViewModel.
- Picking again **adds** to the current selection (duplicates skipped, capped at 10 with a message) rather than
  replacing it.
- No double-tap like was added (the feed never had one); `PostMediaView` keeps tap-to-open.
- The create strip's unselected thumbnails got a 1 dp outline after the journey showed a mostly-white photo was
  invisible.
- compose-reviewer: no blocker/major; three minor findings fixed (draft + items written and deleted in one `@Transaction`, files removed after commit; upload banner says "posts"; a bad photo count returns `Result.failure` instead of throwing).
- The device used for tests and the journey is the `Pixel_8_Pro` AVD (API 37); `medium_phone` no longer exists.

## Remaining open items (default chosen)
- Rate-limit algorithm: fixed window (default) vs sliding window log.
- HLS segment format: fMP4 (default) vs MPEG-TS.
- Reels ordering: newest first (default); ranking stays out of scope with explore.
- Story duration per photo: 5 s (default).
- Visibility cache TTL for media: 60 s (default) — a just-blocked user may still load an already-seen media URL for
  up to a minute.
- Whether the profile Saved tab also lists saved reels: yes, mixed grid (default).
- KMP shared-DTO module: still deferred (DTOs remain duplicated).
- Feed refresh after a cold-start upload (found in the M13 journey, behaviour since v1): `postsChanged` has no replay,
  so a post published by WorkManager right after launch shows only after a refresh. Default: leave until a milestone
  touches the feed (M15), then expose a "stale" flag from the repository instead of an event.
