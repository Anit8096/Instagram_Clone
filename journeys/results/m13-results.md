# M13 journey results — carousel posts

Run 2026-10-06 on the `Pixel_8_Pro` AVD (API 37, `-gpu swiftshader_indirect`) against a throwaway `insta-m13`
Compose stack (M12 server, seeded). Journey: `journeys/m13-carousel.xml`.

| # | Step | Result |
|---|---|---|
| 1 | Sign in by phone as maya.travels | PASS — Home feed (`m13-01-feed.png`) |
| 2 | Profile grid carousel badge | PASS — badge on the oldest post only (`m13-02-profile-grid.png`) |
| 3 | Carousel detail opens on the first photo | PASS — "1/3" chip, first of three dots active (`m13-03-detail-1of3.png`) |
| 4 | Swipe to the next photo | PASS — "2/3", second dot active, different photo (`m13-04-detail-2of3.png`) |
| 5 | Pick three photos in the system picker | PASS — multi-select, "3 photos or videos selected" → Done (`m13-05-picker.png`) |
| 6 | Create screen after picking | PASS — 3 thumbnails, first selected, "3 of 10 photos", "Move earlier" disabled, Original selected (`m13-06-create-3-photos.png`) |
| 7 | 1:1 crop + reorder | PASS — third photo moved to second and still selected, square preview (`m13-07-square-reordered.png`) |
| 8 | Caption | PASS |
| 9 | Share while offline | PASS — "Posting…" banner; WorkManager logged "Constraints not met" and waited (`m13-08-offline-posting.png`) |
| 10 | Force-stop, network on, relaunch | PASS — the worker ran in the new process; server has "m13 carousel journey" with items 0–2, each 920×920 |
| 11 | New post in the feed | PASS after pull-to-refresh — first, three dots, caption (`m13-09-feed-new-carousel.png`) |
| 12 | Thumbnail outline | PASS — mostly-white thumbnails have a visible edge (`m13-10-strip-outline.png`) |

## Findings
- **Fixed during the run:** an unselected, mostly-white photo was invisible in the create strip (no edge against the
  background). Unselected thumbnails now have a 1 dp `outlineVariant` outline.
- **Not fixed (existing behaviour, not carousel-specific):** when a queued post finishes right after a cold start, the
  feed has already loaded and `postsChanged` (no replay) is emitted before anyone collects it, so the new post appears
  only after a refresh. Tracked as an open item in the plan.
- Environment: the old `medium_phone` AVD no longer exists; `Pixel_8_Pro` was used. It needed an unlock swipe after
  boot (instrumented tests fail in direct-boot mode otherwise) and `svc power stayon true`. Compressing three photos
  took ~20 s under the software renderer.
