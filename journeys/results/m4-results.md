# Journeys: M4 social + offline feed

Run 2026-10-04 on emulator `medium_phone` (API 36), debug build against the local Docker backend via `adb reverse`.
Setup through the API: `journey.friend` created with one post ("Hello from a friend"); `journey.user` unfollowed it.

## m4-social (run 2): all 10 actions passed ✅

| # | Action | Result | Evidence |
|---|---|---|---|
| 1 | Main app with "Home" selected | ✅ | [m4-01-home.png](m4-01-home.png) (empty-feed state, Home highlighted) |
| 2 | Tap "Explore" | ✅ | |
| 3 | Enter "journey.fr" in "Search people" | ✅ | field focused at [672,271] |
| 4 | "journey.friend" in results | ✅ | [m4-04-search.png](m4-04-search.png); server `200 GET /api/v1/search/users` |
| 5 | Tap "journey.friend" | ✅ | |
| 6 | Profile with "Follow" button | ✅ | |
| 7 | Tap "Follow" | ✅ | server `200 PUT /api/v1/users/journey.friend/follow` |
| 8 | "Following" shown, followers = 1 | ✅ | [m4-08-following.png](m4-08-following.png) |
| 9 | Tap "Home" | ✅ | |
| 10 | Feed shows journey.friend's "Hello from a friend" | ✅ | [m4-10-feed.png](m4-10-feed.png) |

**Run 1 failed at step 10: an app bug.** The feed listened for "follow changed" only while its composable was on screen,
so a follow made on the Explore tab was never seen and the feed didn't refresh (no `GET /feed` after the follow in the
server log). Fixed: the ViewModel collects change signals and marks the feed stale, and the screen refreshes when shown
(same for Explore). Regression test: `FeedViewModelTest`.

## m4-offline: all 3 actions passed ✅

Backend stopped with `docker compose stop server` (emulator airplane mode doesn't cut the `adb reverse` tunnel).

| # | Action | Result | Evidence |
|---|---|---|---|
| 1 | Force-stop and relaunch the app | ✅ | |
| 2 | Feed shows "Hello from a friend" | ✅ | [m4-offline.png](m4-offline.png): post and photo from the Room and Coil caches |
| 3 | "You're offline. Showing saved posts." shown | ✅ | same screenshot, with Retry |
