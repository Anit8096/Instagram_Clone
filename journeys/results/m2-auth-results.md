# Journey: M2 sign-up, logout, login and session persistence

Run 2026-10-04 on emulator `medium_phone` (API 36, Google Play image, `-gpu swiftshader_indirect`)
against the local Docker backend, debug build with `-Pinsta.apiBaseUrl=http://localhost:8080`
and `adb reverse tcp:8080 tcp:8080`. **Result: all 20 actions passed.**

## Results

### Action: Verify that the login screen is shown with a "Username or email" field, a "Password" field and a "Log in" button ✅
- **Commands**: `adb shell am force-stop com.android.insta`, `adb shell am start -W -n com.android.insta/.MainActivity`
- **Comment**: Confirmed in layout. See *Notes* for an unexplained first-launch anomaly before this run.

### Action: Tap the "Log in" button ✅
- **Commands**: `adb shell input tap 672 1804`

### Action: Verify that two "Required" error messages are shown ✅
- **Screenshot**: [03-required-errors.png](03-required-errors.png)
- **Comment**: Exactly 2 counted in the layout and seen visually in the first pass. The re-run (after the
  base-URL rebuild) confirmed presence only, because of a counting bug in my helper (`grep -c` on single-line JSON).

### Action: Tap "Sign up" ✅
- **Commands**: `adb shell input tap 893 2044`

### Action: Verify that a "Create account" button is shown ✅

### Action: Enter "journey.user" in the "Username" field ✅
- **Commands**: `adb shell input tap 1000 1144`, `adb shell input text "journey.user"`

### Action: Enter "journey.user@example.com" in the "Email" field ✅
- **Commands**: `adb shell input tap 1000 1402`, `adb shell input text "journey.user@example.com"`
- **Comment**: In the first pass I tapped the field label at x=183, which sat under the emulator keyboard's
  floating stylus toolbar, so the text went into the Username field. That was a test-harness error, not an app
  bug. All later taps use x=1000.

### Action: Enter "journey-password" in the "Password" field ✅
- **Commands**: `adb shell input tap 1000 1858`, `adb shell input text "journey-password"`
- **Screenshot**: [08-register-filled.png](08-register-filled.png)
- **Comment**: The keyboard opened and the form scrolled the focused field above it (edge-to-edge IME handling works).

### Action: Tap the "Create account" button ✅
- **Commands**: `adb shell input tap 672 1552`
- **Comment**: Server log: `201 Created: POST - /api/v1/auth/register`.

### Action: Verify that the main app is shown with "Home", "Explore", "Create", "Activity" and "Profile" navigation items ✅
- **Screenshot**: [10-main-app.png](10-main-app.png)

### Action: Tap the "Profile" navigation item ✅

### Action: Verify that "@journey.user" is shown ✅
- **Screenshot**: [12-profile.png](12-profile.png)
- **Comment**: Data loaded from the server (`200 OK: GET - /api/v1/me`).

### Action: Tap the "Log out" button ✅
- **Comment**: Server log: `204 No Content: POST - /api/v1/auth/logout`.

### Action: Verify that the login screen is shown ✅

### Action: Enter "journey.user" in the "Username or email" field ✅

### Action: Enter "journey-password" in the "Password" field ✅

### Action: Tap the "Log in" button ✅
- **Comment**: Server log: `200 OK: POST - /api/v1/auth/login`.

### Action: Verify that the main app is shown with the "Home" navigation item selected ✅

### Action: Force-stop and relaunch the app ✅
- **Commands**: `adb shell am force-stop com.android.insta`, `adb shell am start -W -n com.android.insta/.MainActivity`

### Action: Verify that the main app is shown without asking to log in again ✅
- **Screenshot**: [20-relaunch-still-signed-in.png](20-relaunch-still-signed-in.png)

## Notes

- **First-launch anomaly (not reproduced):** right after the first `android run`, the app showed the Register
  screen ([01-first-launch.png](01-first-launch.png)) although only one activity start was logged. After a
  force-stop and relaunch it opened on Login and stayed there. Worth watching; no cause found.
- **Emulator networking:** with the default base URL `http://10.0.2.2:8080`, the first sign-up attempt hit a
  10 s connect timeout. The app handled it correctly ("Can't reach the server…", form re-enabled), but the
  emulator's NAT path to Docker Desktop's published port didn't deliver traffic on this machine. Workaround
  used: `adb reverse tcp:8080 tcp:8080` plus base URL `http://localhost:8080`.
- **Emulator GPU:** the default (host GPU, NVIDIA RTX 5050) emulator boot exited early; `-gpu swiftshader_indirect` works.
