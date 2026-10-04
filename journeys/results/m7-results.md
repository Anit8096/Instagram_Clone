# Journey results — M7 notifications (`journeys/m7-notifications.xml`)

Device: emulator `medium_phone` (API 36), backend via `adb reverse tcp:8080 tcp:8080`, debug build with
`-Pinsta.apiBaseUrl=http://localhost:8080`. No Firebase project configured (server runs `NoopPushSender`, app has no
`google-services.json`), so FCM delivery itself is not exercised; everything else is.

Setup: signed in as `journey.user`; POST_NOTIFICATIONS revoked and its user flags cleared beforehand.

| # | Step | Result |
|---|------|--------|
| 1 | Home screen shown | PASS |
| 2 | Activity tab gets a badge without any tap after the harness comments as `journey.friend` | PASS (~1 s, via `notification.new` on the socket; `m7-02-badge.png`) |
| 3 | Tap Activity | PASS |
| 4 | Row "journey.friend commented: Nice shot from M7!" listed, highlighted as new, with post thumbnail; badge cleared | PASS |
| 5 | "Turn on notifications" card shown; tap Turn on | PASS (system dialog "Allow Insta to send you notifications?") |
| 6 | Tap Allow | PASS (`granted=true`) |
| 7 | Card gone | PASS |
| 8 | Tap the row → Comments shows "Nice shot from M7!" | PASS |
| 9 | `am start -d insta://chat/journey.friend` while running → conversation opens | PASS (delivered to `onNewIntent`, singleTop) |
| 10 | Back → Messages inbox | PASS (synthetic back stack Home › Inbox › Thread; `m7-10-inbox.png`) |

Extra checks:
- Cold start: `am force-stop`, then `am start -d insta://user/journey.friend` → journey.friend's profile opens directly
  (the link is held in `PendingDeepLinks` through the splash screen).
- A like from `journey.friend` after reinstall shows the badge again (V3 dedup: one like notification per actor/post).

Finding fixed during the run:
- The badge was only visual. Material 3 navigation items clear their icon's semantics when a label is shown, so the
  `Badge`'s content description never reached accessibility services. The count is now set as the navigation item's
  `stateDescription` ("1 new notification"). The CLI layout dump doesn't print state descriptions, so this was checked
  in code, not on device.
