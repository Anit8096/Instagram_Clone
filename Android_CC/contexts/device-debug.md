# Context: debugging on a device

Mode: observe before changing code.
- Reproduce with `android run`; read `adb logcat -d` filtered by tag/package; look at `android layout` and screenshots.
- Network: correlate client logs (`Http` tag) with server logs; for local backends verify
  emulator→host reachability (`10.0.2.2` vs `adb reverse` + `localhost`).
- Emulator won't boot: `-gpu swiftshader_indirect`; check `~/.android/<avd>/emulator.log`.
- Separate harness/tooling problems from app bugs in the report.
