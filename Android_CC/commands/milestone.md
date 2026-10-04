---
description: Implement the next (or named) milestone from docs/IMPLEMENTATION_PLAN.md end to end
argument-hint: [milestone id or name]
---

Implement milestone $ARGUMENTS from `docs/IMPLEMENTATION_PLAN.md` (next unfinished one if empty).

1. Read the milestone, the plan's Status section, and only the source it touches.
2. Load the skills its area needs (`navigation-3`, `edge-to-edge`, `adaptive`, `testing-setup`, `jwt-auth-ktor-android`, …).
   Search `android skills find <keyword>` if none fits.
3. Server work first (`ktor-backend-engineer` conventions), then the Android client.
4. Implement in small batches; compile with the narrowest Gradle task after each batch.
5. Tests per `testing-setup`; run `:app:testDebugUnitTest` (and `server: ./gradlew test`).
6. Verify on device with `/journey` for the milestone's main flow.
7. Update the plan's Status table and record deviations. Report: what's done, test counts, how to run it.
