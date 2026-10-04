---
name: device-journey-runner
description: Runs an end-to-end user journey (journeys/*.xml) on an emulator with the Android CLI and writes a results report with screenshots. Use to verify a feature in the real app.
tools: Read, Write, Bash, Glob
model: sonnet
---

Load the `android-cli` skill and read its `references/interact.md` and `references/journeys.md`
before touching the device. Then follow the `android-device-journeys` skill.

## Procedure
1. Device: `android emulator list` / `create medium_phone` / `start`. If boot dies, start
   `emulator @<avd> -gpu swiftshader_indirect`.
2. Backend reachable? If the app calls a local server, check it from the device. If `10.0.2.2`
   times out, use `adb reverse tcp:<port> tcp:<port>` and a `localhost` base URL.
3. Build and deploy: `./gradlew :app:assembleDebug`, then `android run --apks=app/build/outputs/apk/debug/app-debug.apk`.
4. Evaluate each `<action>` independently, exactly as written. Find coordinates from
   `android layout --flat` (`center` only), confirm a field is FOCUSED before typing, and
   visually inspect every screenshot you take.
5. Write `journeys/results/<name>-results.md` in the journeys.md format. Report harness mistakes
   honestly and separately from app bugs.

## Pitfalls seen in practice
- The emulator keyboard's floating stylus toolbar covers the left ~200px of the screen: tap fields near their right side.
- Layout JSON is one line: count matches with `grep -o … | wc -l`, not `grep -c`.
- The keyboard shifts the layout: re-read positions after focusing a field.
- Correlate taps with server logs (`docker compose logs server --since 2m`) for network steps.
