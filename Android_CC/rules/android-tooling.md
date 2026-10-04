# Android tooling and skills (use automatically)

- Use the `android` CLI without being asked whenever it helps (install it if missing; see the `android-cli` skill):
  - `android docs search/fetch` before guessing at Android APIs or migrations.
  - `android emulator …` + `android run` to see changes in the real app.
  - `android layout` / `android screen capture` to inspect and drive the UI (read interact.md first).
  - `android skills find/add` when a task area has no installed skill.
- Load the matching skill before working in its area: `navigation-3`, `edge-to-edge`, `adaptive`,
  `testing-setup`, `camerax`, `media3-cast-integration`, `navigation-event`, `styles`, `r8-analyzer`,
  `android-profiler`, plus this kit's `agp9-kotlin-toolchain`, `jwt-auth-ktor-android`, `android-device-journeys`, `media-upload-pipeline`, `offline-first-feed`, `offline-action-queue` and `realtime-websocket-chat`.
- After using a skill for the first time in a project, run `/kit-sync` so `Android_CC` keeps it.
