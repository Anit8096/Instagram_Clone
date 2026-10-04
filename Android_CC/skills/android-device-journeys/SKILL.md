---
name: android-device-journeys
description: Write journey XML acceptance tests and evaluate them on an emulator with the Android CLI (layout dumps, adb input, screenshots), including emulator boot, local-backend networking and reporting. Use to verify a feature end-to-end in the real app.
---

# On-device journeys with the Android CLI

Prerequisite: the `android-cli` skill, and its `references/interact.md` and `references/journeys.md`.

## 1. Write the journey
Store it in `journeys/<milestone>-<feature>.xml`. Make each `<action>` a single interaction or a
`Verify …` expectation, with exact on-screen text in quotes. Use fresh test data (a new username)
so it can be re-run, and end with a persistence check (force-stop + relaunch).

## 2. Prepare the device
```sh
android emulator create medium_phone        # once; downloads an image
android emulator start medium_phone
# if the boot exits early (common with brand-new GPU drivers):
"$ANDROID_SDK/emulator/emulator" @medium_phone -gpu swiftshader_indirect -no-snapshot-load
adb shell getprop sys.boot_completed        # 1 = ready
```

## 3. Reach a local backend
The default emulator→host alias is `http://10.0.2.2:<port>`. If requests time out (Docker Desktop
port proxy/VPN quirks), use the adb tunnel instead:
```sh
adb reverse tcp:8080 tcp:8080
./gradlew :app:assembleDebug -PapiBaseUrl=http://localhost:8080   # base URL read from a Gradle property into BuildConfig
```
Allow cleartext only for `10.0.2.2`/`localhost` in a debug-only `network_security_config.xml`.

## 4. Drive the app
```sh
android run --apks=app/build/outputs/apk/debug/app-debug.apk
android layout --flat                       # JSON on ONE line: parse "center" only
adb shell input tap X Y
adb shell input text "test.user"            # %s for spaces
android screen capture -o journeys/results/NN-step.png   # always look at it
```
Helpers that worked:
```sh
centerOf() { android layout --flat | grep -oE '\{[^{}]*\}' | grep -F "\"text\":\"$1\"" \
  | grep -oE '"center":"\[[0-9]+,[0-9]+\]"' | head -1 | grep -oE '[0-9]+,[0-9]+' | tr ',' ' '; }
focused()  { android layout --flat | grep -oE '\{[^{}]*"state":\["FOCUSED"\][^{}]*\}' | grep -oE '"center":"[^"]*"'; }
```

## Seeding test data
- Gallery photo for picker tests: `adb shell screencap -p /sdcard/Pictures/p.png` then
  `adb shell am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d file:///sdcard/Pictures/p.png`;
  confirm with `adb shell content query --uri content://media/external/images/media --projection _display_name`.
- Reset server-side state the journey asserts on (e.g. clear a field through the API) so a "Verify" can't pass on stale data.
- `connectedAndroidTest` **uninstalls the app** when it finishes: re-run sign-in/setup afterwards.

## Pitfalls
- Exact-match helpers can hit the wrong screen (e.g. a search result with the same text as a profile title): after each navigation, assert something only that screen has.
- For text fields whose label contains non-ASCII characters, target the `EDITABLE` element instead of the label.
- When a journey is re-run against persisted data, change the asserted texts so checks can't pass on the previous run's data.
- Git Bash rewrites device paths (`/sdcard/...`) into Windows paths: prefix `MSYS_NO_PATHCONV=1` for `adb pull/push/shell`.
- Windows `curl -F "file=@/c/..."` can't read Git-Bash paths: `cd` to the folder and use a relative path.
- Airplane mode doesn't cut `adb reverse`; to test "server unreachable", stop the backend.
- "Selected" state of a navigation item lives on its container, not the label text: verify selection visually or by the parent element.
- Layout JSON escapes non-ASCII (`…` → `…`): match text by **prefix**, without the closing quote.
- Guard every tap: if a lookup returns nothing, skip and report it. `adb shell input tap` with no coordinates errors,
  and the rest of a scripted chain then acts on the wrong screen.
- Images usually expose their caption (or alt text) as `content-desc`, not a fixed label; check the layout before assuming.
- The system photo picker (recent versions) needs **Done** after selecting, even in single-select mode.
- A "Verify" only inspects the current screen: content pushed below the fold fails it. That's often a real layout bug (e.g. an unclamped image aspect ratio), not a test problem.
- The floating stylus toolbar covers the left edge: tap text fields near their right end.
- Confirm FOCUSED before `input text`, or the text lands in the previous field.
- Count matches with `grep -o … | wc -l` (not `grep -c`).
- The keyboard moves the layout: re-read coordinates after focusing.
- The first `android layout` installs an instrumentation server; give it a moment.
- For network steps, check server logs for the matching request and status.
- Badges on navigation items don't show in the layout dump (M3 clears icon semantics); verify them with a screenshot.
  The dump also omits `stateDescription`.
- Runtime-permission steps: reset first with `pm revoke <pkg> <perm>` and `pm clear-permission-flags <pkg> <perm> user-set user-fixed`, or the dialog never appears.
- Deep-link steps: `am start -a android.intent.action.VIEW -d <uri>` while running is delivered to `onNewIntent`
  (look for "intent has been delivered to currently running top-most instance"); also test a cold start after `am force-stop`.
- Harness API calls (acting as a second user) go in a small script file rather than a one-line shell chain; it's easier to re-run and to read in the results.

## 5. Report
`journeys/results/<name>-results.md` in the journeys.md format: ✅/❌ per action, commands,
screenshots and comments. Separate harness mistakes from app bugs; note anomalies you couldn't reproduce.
