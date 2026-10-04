# Testing

- Follow the `testing-setup` skill; respect the project's existing frameworks.
- Every ViewModel and repository with logic gets unit tests (fakes over mocks; Mockk only if unavoidable).
- Network code is tested through the real client factory with Ktor `MockEngine`.
- Compose behaviour tests run locally with Robolectric in `src/test`, matching by semantics, and
  include a state-restoration check.
- Navigation logic (back stacks, back handling) has plain JVM tests.
- DI graph verified (`Koin verify()`).
- Backend: `testApplication` + Testcontainers; tests skip (not fail) without Docker.
- A feature is done when its tests pass **and** its main flow passed a device journey (`/journey`).
- Workers: `work-testing` `TestListenableWorkerBuilder` + a custom `WorkerFactory` that injects fakes.
- Room DAOs: instrumented tests against an in-memory database (real SQLite), run with `connectedDebugAndroidTest`
  (it uninstalls the app afterwards).
- When a device journey finds a bug, add a unit test that reproduces it before (or with) the fix.
- If a behaviour change legitimately invalidates an old test's input, change the input to keep testing the original
  intent, and cover the new behaviour with its own test. Never just update the expected value.
- Never delete, skip or weaken a test to get green; report failures with output.
