# Spec — Insta v2

Approved 2026-10-06. v2 builds on the finished v1 app (M1–M10). v1 documents are archived in `docs/v1/`:
the v1 plan (`docs/v1/IMPLEMENTATION_PLAN.md`) and the Google-first + phone OTP auth spec (`docs/v1/SPEC-auth.md`,
still referenced from code comments as "docs/SPEC.md"). Everything v1 decided (stack, auth, offline model) stays
unless this spec changes it.

## Summary
- **Goal:** a deeper portfolio showcase — media pipelines, Redis-backed infrastructure, privacy rules everywhere,
  richer real-time features.
- **Platforms / hosting:** unchanged. Android only (single app module) + Ktor server in local Docker Compose.

## In scope
1. **Redis infrastructure** (Lettuce, coroutines)
   - **Job queue** on Redis Streams: consumer groups, retries, dead-letter stream. Used for video transcoding,
     story expiry and upload clean-up.
   - **Rate limiting** in Redis: OTP limits and per-IP API limits.
   - **Cache:** profiles, counters, story trays; TTL + explicit invalidation.
2. **Carousels:** up to 10 items per post, photos and videos mixed; one aspect ratio per post (from the first item);
   pager with indicator; videos inside carousels autoplay muted in the feed.
3. **Video & Reels**
   - Clips ≤ 45 s. The app compresses to 1080p H.264 with Media3 Transformer, then uploads with a resumable
     chunked upload.
   - The server transcodes with FFmpeg to HLS (3 renditions) + poster frame; playback with Media3 ExoPlayer.
   - **A reel is a single vertical video post.** Reels appear only in the Reels tab (full-screen vertical swipe,
     autoplay, preloading) and in a Reels grid on the profile — not in the home feed.
4. **Navigation:** tabs become Feed · Explore · Create · **Reels** · Profile. Activity moves to a heart icon (with its
   badge) in the Home top bar next to Messages.
5. **Stories:** photo only, 24 h. Tray on top of the feed, viewer (tap / hold / swipe), seen state, viewers list for
   the author. Expiry via a delayed job; reads also filter on expiry time.
6. **Privacy & safety**
   - **Private accounts:** follow requests (approve / decline); posts, reels and stories hidden from non-followers;
     existing comments by a private user stay visible.
   - **Block:** hides profiles, content, comments and likes both ways; removes follows and pending requests both
     ways; stops 1:1 DMs. Both may stay in a shared group, with a warning.
   - **Mute:** silently hides someone's posts and/or stories (and reels) from your feed, tray and Reels tab.
7. **Social:** saved posts (private Saved grid on your profile); one level of comment replies + comment likes;
   #hashtags and @mentions (tappable, hashtag page, mention notifications).
8. **Group DMs:** up to 16 members; creator names the group; any member can add; members can leave; the creator can
   remove and rename. Text messages + shared posts/reels. Sharing a post/reel into any DM (1:1 or group).
   Read receipts stay 1:1 only.
9. **Offline**
   - The action queue gains: save/unsave, replies, comment likes, mute/unmute, story views, group messages,
     shared-post messages.
   - Carousel, reel and story uploads go through WorkManager (like v1 posts).
   - Block and follow requests are online-only (optimistic, revert on failure).

## Out of scope
Reporting / moderation, ranked explore (stays "recent posts from accounts you don't follow"), video stories, story
replies, highlights, in-app video trim, KMP / iOS, cloud hosting, a real SMS provider, group admin roles.

## Data model (new or changed)
| Area | Change |
|---|---|
| Media | `media` gains `type` (photo/video), `width`, `height`, `duration_ms`, `status` (pending/processing/ready/failed), poster + HLS paths |
| Posts | `post_media` (post_id, position, media_id) replaces `posts.media_id`; `posts.kind` (post/reel); `posts.status` (processing/published/failed) |
| Uploads | `upload_sessions` (resumable chunks: owner, size, received bytes, status, expires) |
| Jobs | `jobs` table (type, payload, status, attempts, run_at, last_error) — Postgres is the source of truth, Redis Streams the transport |
| Stories | `stories` (author, media, created_at, expires_at), `story_views` (story, viewer, viewed_at) |
| Privacy | `users.is_private`, `follow_requests`, `blocks`, `mutes` (posts / stories flags) |
| Social | `saved_posts`, `comments.parent_id` (one level) + `reply_count`, `comment_likes` + `like_count`, `hashtags`, `post_hashtags`, `mentions` |
| Messaging | `conversations.kind` (direct/group), `name`, `created_by`; `conversation_members` (role creator/member, joined_at, left_at, last_read); `messages.kind` (text/shared_post/system), `shared_post_id` |
| Notifications | new types: follow_request, follow_accepted, mention, reply, comment_like, group_added, media_failed |

## ASSUMPTIONS
1. **Media access:** from the privacy milestone onward, media GETs require auth and check visibility (private/block),
   cached in Redis for a short TTL; Coil and ExoPlayer send the bearer token. Until then v1's public UUID URLs stay.
   Avatars stay visible to every signed-in user except across a block.
2. **Redis down:** cache fails open (read Postgres); general rate limits fail open, OTP limits fail closed; uploads
   are still accepted and a reconciler re-enqueues due `jobs` rows when Redis returns.
3. **Transcoding:** HLS with fMP4 segments, 6 s, renditions 1080p / 720p / 360p; 2 concurrent transcodes; 3 retries,
   then dead-letter and the post shows "Processing failed" to its author. A post is hidden until all its media is
   ready; the author sees "Processing…".
4. **Uploads:** our own chunked protocol (5 MB chunks, resume by offset), not tus. Max compressed clip ~150 MB.
   Abandoned sessions expire after 24 h.
5. **Hashtags / mentions** are parsed on the server and stored. Mentions only notify users who can see the content.
   Hashtag pages are chronological.
6. **Stories:** seen state per viewer in Postgres; tray order (unseen first) cached in Redis with a short TTL.
7. **Going private** → pending requests show in Activity; switching back to public auto-approves pending requests.
8. **Groups:** existing 1:1 conversations migrate to `kind=direct` with two members. The socket fans out to members;
   no multi-instance pub/sub.
9. **Mute** has separate posts and stories switches; muting posts also hides that person's reels in the Reels tab.
10. **Reels** must be vertical (aspect 9:16 up to 4:5); carousel videos follow the post's aspect ratio (center crop).

## OPEN RISKS
- **FFmpeg:** bigger Docker image, CPU-heavy transcodes, slow emulator journeys with video.
- **Video testing:** real-FFmpeg server tests need `ffmpeg` on the test host (CI installs it); Transformer and
  ExoPlayer are covered by instrumented tests and journeys, not unit tests.
- **Visibility leaks:** privacy and blocks cut across every query (feed, explore, search, comments, likes,
  notifications, DMs, stories, reels, media). Mitigation: one central visibility rule + an endpoint × relationship
  test matrix.
- **Retrofitting:** privacy lands after media and stories, so those features are revisited in the privacy milestone.
- **Group DM migration** touches M6/M7 code: unread counts, pushes, deep links keyed by username.
- **ExoPlayer memory:** the Reels pager needs a small player pool and preloading that fits low-RAM emulators.
- **Cache drift:** counters and trays can briefly differ from Postgres; TTLs bound the staleness.
- **Scope:** v2 is roughly twice v1 — 13 milestones.
