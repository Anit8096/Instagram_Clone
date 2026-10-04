---
name: release-hardening-android-ktor
description: Final-mile work for a Compose app with a Ktor backend. Account deletion with re-authentication, idempotent demo seed data through real services, GitHub Actions CI for Android plus Ktor with Testcontainers, an accessibility/dark-theme/font-scale pass, and a showcase README. Use when preparing a project for review, release or a portfolio.
---

# Release hardening (Compose + Ktor)

## Account deletion
- `DELETE /me` with a body: `{password}` for password accounts, or a **fresh** `{googleIdToken}` whose `sub` matches
  the linked Google account for Google-only accounts.
- A failed re-auth returns **403** with a specific code (e.g. `REAUTH_FAILED`), never 401. A 401 makes the client's
  bearer plugin treat it as an expired access token, refresh, and possibly sign the user out.
- Let foreign keys `ON DELETE CASCADE` do the heavy lifting (posts, comments, likes, follows, conversations,
  notifications, device and refresh tokens, so every session dies at its next refresh). Then fix what cascades
  can't: **denormalised counters on other people's rows** (likes and comments the user left), in the same
  transaction, before deleting the user.
- Collect file keys inside the transaction; delete the files **after commit**, so a rollback never loses media.
- Client: on success, wipe local data and the session without calling logout (the server already revoked
  everything); keep the dialog open with a specific message on 403. Offer "Confirm with Google" only when Google is
  configured. Move sign-out and deletion into a Settings screen rather than the profile header.

## Demo seed data
- A Kotlin `main` in the server module that builds the same Koin graph and calls the **real services** (register,
  upload, create post, follow, like, comment, send). Images then go through the same processing as user uploads.
- Generate images in-process (`java.awt` with `java.awt.headless=true`: gradients + shapes, initials for avatars,
  `Random(seed)` so output is deterministic). No binary assets in the repo.
- **Idempotent**: check for a sentinel account first. Cover it with an integration test that seeds twice and asserts
  the row counts.
- Run it in the container with `java -cp "/app/lib/*" <MainKt>` (`installDist` puts every jar there), plus a Gradle
  `JavaExec` task for local runs.
- Gotcha: `/app/lib/*` inside a KDoc block opens a **nested comment** in Kotlin (`/*`), which shows up as
  "Unclosed comment". Use `//` comments for such paths.

## CI (GitHub Actions)
- Two jobs: the server (`setup-java` 21 + `gradle/actions/setup-gradle`, `./gradlew test`; Testcontainers works
  on `ubuntu-latest`) and Android (`:app:lintDebug :app:testDebugUnitTest :app:assembleDebug`). Upload reports as
  artifacts (`if: failure()` / `always()`), and use `concurrency` to cancel superseded runs.
- Look up the latest major tag of each action before writing the workflow (e.g. the GitHub releases API).
- Windows-authored repos: mark the wrappers executable in git (`git update-index --chmod=+x gradlew`) and also
  `chmod +x` in the step.
- Secrets-dependent plugins (google-services) applied only when their file exists, so CI needs no dummy secrets.

## Accessibility and theming pass
- Grep for `contentDescription = null` and nested `clickable` without a label. Decorative images stay null; clickable
  avatars need a description such as "Open X's profile" and `Role.Button`.
- Verify on device with `adb shell cmd uimode night yes` and `adb shell settings put system font_scale 1.3`; take
  screenshots of the busiest screens (profile header, feed card). Reset both afterwards.
- The template theme already follows the system and dynamic colour; strip its leftover commented-out colours.

## Showcase README
- Screenshots from journey runs (light, dark, large font), a features list, a Mermaid architecture diagram,
  two short "how it works" explainers for the trickiest mechanisms, quickstart commands with seeded credentials,
  test commands with counts, and a "design decisions" list that points to the plan's deviation log.
