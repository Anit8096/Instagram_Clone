# Journey results — M8 (`journeys/m8-account.xml`)

Device: emulator `medium_phone` (API 36), backend via `adb reverse tcp:8080 tcp:8080`, debug build with
`-Pinsta.apiBaseUrl=http://localhost:8080`. Server rebuilt from this branch and seeded with
`docker compose exec server java -cp "/app/lib/*" com.android.insta.server.seed.SeedKt`
("Seeded 6 demo accounts and 18 posts"). Throwaway account `delete.me` created through the API (201).

| # | Step | Result |
|---|------|--------|
| 1 | Log in as delete.me | PASS (empty feed with "Find people") |
| 2 | Profile → Settings | PASS (the profile's old "Log out" button is now "Settings") |
| 3 | Settings shows Log out and Delete account | PASS |
| 4 | Delete account, wrong password, Delete | PASS (no Google option, because Google sign-in isn't configured in this build) |
| 5 | "That didn't match…" shown, dialog still open | PASS (403 REAUTH_FAILED doesn't trigger a token refresh or a sign-out; `m8-05-reauth-error.png`) |
| 6 | Correct password, Delete | PASS |
| 7 | Sign-in screen shown | PASS (server login for delete.me now returns 401) |
| 8 | Logging in as delete.me fails | PASS ("Incorrect username/email or password") |
| 9 | Log in as maya.travels, feed has other demo accounts' posts | PASS (avatars, generated photos, likes, comments, Activity badge; `m8-09-seeded-feed.png`) |
| 10 | Feed readable in dark theme (`cmd uimode night yes`) | PASS (`m8-10-dark.png`) |
| 11 | Profile at 130 % font (`settings put system font_scale 1.3`) | PASS (header, counts, bio and buttons fit; `m8-11-large-font.png`) |

Night mode and font scale were reset afterwards.
