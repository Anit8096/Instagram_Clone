---
description: Run a journeys/*.xml end-to-end test on an emulator with the Android CLI and write a results report
argument-hint: <journeys/file.xml>
---

Evaluate the journey $ARGUMENTS on a device, following the `android-device-journeys` skill and
the `android-cli` skill's `references/journeys.md` exactly.

1. Start/boot an emulator (swiftshader fallback); make the backend reachable (`adb reverse` if needed).
2. Build the debug APK and deploy it with `android run`.
3. Evaluate every action in order, independently; inspect each screenshot visually.
4. Write `journeys/results/<name>-results.md` with ✅/❌, commands, screenshots and comments.
