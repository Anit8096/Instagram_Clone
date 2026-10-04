# Journey: M6 direct messages in real time

Run 2026-10-04 on emulator `medium_phone` (API 36) against the local Docker backend (`adb reverse`).
Harness actions (as journey.friend, through the API): reply after step 6, mark read after step 7.

**Run 2: all 8 actions passed ✅** ([m6-02-inbox.png](m6-02-inbox.png), [m6-08-seen.png](m6-08-seen.png))

| # | Action | Result | Evidence |
|---|---|---|---|
| 1 | Tap "Messages" in the Home top bar | ✅ | |
| 2 | Messages screen shown | ✅ | conversation with journey.friend listed |
| 3 | Back → Explore → search → open journey.friend | ✅ | profile with "Message" button |
| 4 | Tap "Message" | ✅ | thread opened |
| 5 | Send "Are you around?" | ✅ | |
| 6 | Shown without "Sending…" | ✅ | |
| 7 | Reply "Yes, right here!" appears with no taps | ✅ | ~1 s after the API call, via the WebSocket |
| 8 | "Seen" under the last own message | ✅ | ~1 s after the friend's read call |

**Run 1 failed at step 7: an app bug.** The shared Ktor client didn't have the client `WebSockets` plugin installed,
so every connection attempt threw ("Plugin WebSockets is not installed") and the realtime socket never connected.
Fixed in `createHttpClient` (with a 15 s ping); regression test `ChatTest.shared http client supports websockets`.
Run 1's step 3/4 checks were also harness false positives (text matched the search result, not the profile). Run 2
re-checked each screen. Message texts were changed for run 2 so verifications couldn't pass on run 1's history.
