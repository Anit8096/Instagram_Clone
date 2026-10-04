# Journey: M3 create post, view, delete and edit profile

Run 2026-10-04 on emulator `medium_phone` (API 36), debug build against the local Docker backend
(`adb reverse tcp:8080 tcp:8080`, `-Pinsta.apiBaseUrl=http://localhost:8080`), signed in as `journey.user`.
Gallery photo seeded with `adb shell screencap -p /sdcard/Pictures/journey_photo.png` plus a media-scan broadcast.

**Final result (run 5): all 19 actions passed.** Earlier runs found two real app bugs, which are now fixed (see *Run history*).

## Results (run 5)

### Action: Verify that the main app is shown with "Home", "Explore", "Create", "Activity" and "Profile" navigation items ✅
- **Screenshot**: [m3-01-main.png](m3-01-main.png)

### Action: Tap the "Create" navigation item ✅

### Action: Verify that a "Choose photo" button is shown ✅
- **Screenshot**: [m3-03-create.png](m3-03-create.png)

### Action: Tap the "Choose photo" button ✅
- **Comment**: The system photo picker opened ("Insta will only have access to the photos you select"); no storage permission requested.

### Action: Tap the first photo in the photo picker ✅
- **Commands**: `adb shell input tap 110 1580`

### Action: Tap "Done" in the photo picker ✅
- **Comment**: Step added after run 1. This picker version asks for confirmation even in single-select mode.

### Action: Verify that the selected photo preview and a "Write a caption…" field are shown ✅
- **Screenshot**: [m3-07-preview.png](m3-07-preview.png)

### Action: Enter "First journey post" in the "Write a caption…" field ✅
- **Commands**: `adb shell input tap 1000 1891`, `adb shell input text "First%sjourney%spost"`

### Action: Tap the "Share" button ✅
- **Comment**: Server: `201 POST /api/v1/media`, then `201 PUT /api/v1/posts/{client id}`. It was preceded by a transparent
  `200 POST /api/v1/auth/refresh` because the access token had expired: the M2 refresh logic worked live.

### Action: Verify that the profile is shown with "1" post ✅
- **Screenshot**: [m3-10-profile.png](m3-10-profile.png)

### Action: Tap the first photo in the profile grid ✅

### Action: Verify that the post screen shows the caption "First journey post" ✅
- **Screenshot**: [m3-12-detail.png](m3-12-detail.png)

### Action: Tap the "Delete post" button ✅

### Action: Tap "Delete" in the confirmation dialog ✅
- **Screenshot**: [m3-13-dialog.png](m3-13-dialog.png)
- **Comment**: Server: `204 DELETE /api/v1/posts/{id}`.

### Action: Verify that the profile shows "No posts yet" ✅
- **Screenshot**: [m3-15-deleted.png](m3-15-deleted.png)

### Action: Tap the "Edit profile" button ✅

### Action: Enter "Hello from the journey" in the "Bio" field ✅
- **Screenshot**: [m3-17-edit.png](m3-17-edit.png)

### Action: Tap the "Save" button ✅
- **Comment**: Server: `200 PATCH /api/v1/me`.

### Action: Verify that the profile shows the bio "Hello from the journey" ✅
- **Screenshot**: [m3-19-bio.png](m3-19-bio.png)

## Run history

| Run | Outcome | Cause | Action taken |
|---|---|---|---|
| 1 | ❌ step 6 (preview not shown) | Journey definition: the system photo picker needs "Done" | Added the "Tap Done" step |
| 2 | void | Harness bug: JSON escapes `…` as `…`, so my exact-text lookup found nothing and taps went out without coordinates | Prefix matching; taps guarded when no target is found |
| 3 | ❌ step 12 (caption not visible) | **App bug**: a ~9:20 portrait photo filled the screen, so the caption was off-screen | Server center-crops to 4:5…1.91:1 (Instagram range); client clamps the aspect ratio too; new server test |
| 4 | ❌ step 19 (bio not shown) | **App bug**: the profile refreshed only when the cached session user changed, which has no bio | `ProfileRepository.profileChanged` signal; test now covers a bio-only edit |
| 5 | ✅ all 19 | | |

Setup notes: `connectedDebugAndroidTest` uninstalls the app afterwards, so the session was wiped and I signed in
again before the runs. The bio was reset to empty through the API before run 5 so step 19 couldn't pass on stale data.
