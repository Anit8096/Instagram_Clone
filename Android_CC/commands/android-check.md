---
description: Full verification loop for the Android app (compile, unit tests, lint, optional device journey)
argument-hint: [quick | full | journey-file]
---

Verify the current state of the project ($ARGUMENTS, default `quick`).

**quick**: `./gradlew :app:compileDebugKotlin :app:testDebugUnitTest`
**full**: `./gradlew build` (lint + debug/release tests + APKs); also `cd server && ./gradlew test` if `server/` exists and Docker is running.
**journey file**: full, plus run that journey with the `device-journey-runner` agent.

Report per suite (tests / failures from `app/build/test-results/**.xml`), the lint error/warning
counts from `app/build/reports/lint-results-debug.txt`, and any failure with its root cause.
Don't "fix" by deleting or skipping tests.
