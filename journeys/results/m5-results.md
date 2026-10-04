# Journeys: M5 likes, comments and the offline action queue

Run 2026-10-04 on emulator `medium_phone` (API 36). The debug build was installed over the M4 build, so Room's v2→v3
auto-migration ran on the device (no crash, cached feed intact).

## m5-engagement: all 7 actions passed ✅
| # | Action | Result | Evidence |
|---|---|---|---|
| 1 | Feed shows "Hello from a friend" with "0 likes" | ✅ | |
| 2 | Tap "Like" | ✅ | |
| 3 | "1 like" and "Unlike" button | ✅ | [m5-03-liked.png](m5-03-liked.png); server `200 PUT …/like` |
| 4 | Tap "Add a comment" | ✅ | |
| 5 | Enter "Nice shot!" | ✅ | |
| 6 | Tap "Post comment" | ✅ | server `201 PUT …/comments/{client id}` |
| 7 | "Nice shot!" by journey.user, no "Sending…" | ✅ | [m5-07-comment.png](m5-07-comment.png) |

## m5-offline-queue: all 7 actions passed ✅
Backend stopped before step 1, started right after step 6.
| # | Action | Result | Evidence |
|---|---|---|---|
| 1 | Back to feed | ✅ | |
| 2 | Tap "Unlike" | ✅ | |
| 3 | "0 likes" and "Like" button (offline, optimistic) | ✅ | |
| 4 | Tap "View 1 comment" | ✅ | |
| 5 | Enter "Offline hello", tap "Post comment" | ✅ | |
| 6 | "Offline hello" with "Sending…" | ✅ | [m5-offline-06-sending.png](m5-offline-06-sending.png) |
| 7 | Within 90 s, "Offline hello" without "Sending…" | ✅ | delivered ~10 s after restart, in order: `200 DELETE …/like`, `201 PUT …/comments/{id}`; [m5-offline-07-delivered.png](m5-offline-07-delivered.png) |

**Observed limitation (not a journey failure):** while offline, the comments screen can't load the existing server
comments (only the queued one shows, plus a "Can't reach the server · Retry" message), because comment lists aren't cached yet.
