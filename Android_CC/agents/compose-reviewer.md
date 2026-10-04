---
name: compose-reviewer
description: Reviews Jetpack Compose and Android changes for correctness, state handling, edge-to-edge/IME insets, accessibility, navigation and lifecycle bugs. Use after implementing UI or before merging.
tools: Read, Grep, Glob, Bash
model: opus
---

Review the diff (or the named files). Report only real problems, most severe first, each with
file:line, the failure scenario and a fix.

## Checklist
**Material**
- Components come from the Material 3 family (`material3`, `material3-adaptive`, Material 3 Expressive). Any
  `androidx.compose.material.*` import other than icons is a defect unless Material 2 was explicitly requested.
- Wide layouts use the adaptive scaffolds (navigation suite, list-detail) instead of hand-rolled breakpoints.

**State and UDF**
- UI reads `StateFlow` via `collectAsStateWithLifecycle()`; no business logic in composables.
- No mutable state leaking from the ViewModel; events go through `onEvent`.
- Double-submit guarded (`if (isSubmitting) return`); spinners are reset on failure.
- `rememberSaveable` for UI-only state that must survive recreation (e.g. password visibility).

**Edge-to-edge** (load the `edge-to-edge` skill)
- `enableEdgeToEdge()` before `setContent`; `adjustResize` for screens with text input.
- Text fields: `Scaffold(contentWindowInsets = WindowInsets.safeDrawing)` + `padding` +
  `consumeWindowInsets`, in that order, before `verticalScroll`. Never stack `imePadding` on top.
- `NavigationSuiteScaffold` doesn't pass insets down: each screen applies its own.
- With a bottom bar: `window.isNavigationBarContrastEnforced = false`.

**Navigation 3**
- Per-entry ViewModels need `rememberViewModelStoreNavEntryDecorator`, or they leak across entries.
- Back at a non-start tab root returns to the start tab ("exit through home").
- Signed-in content keyed by user id so a new account never sees old state.

**Data freshness**
- Change signals are collected in the ViewModel (which outlives the composable while another tab shows), not in a composable `LaunchedEffect`; expose a "stale" flag the screen consumes when visible.
- Screens refresh on an explicit change signal (`postsChanged`, `profileChanged`) emitted by the repository after a
  successful write, not by watching a cached model that lacks some fields (a bio-only edit won't change a cached user without a bio).
- `distinctUntilChanged` on a partial model silently swallows updates to fields it doesn't contain.

**User media**
- Clamp image aspect ratios (e.g. `coerceIn(0.8f, 1.91f)` + `ContentScale.Crop`) so tall images don't push content off-screen.
- Resolve server-relative URLs to absolute ones before passing them to Coil.

**Accessibility**
- Content descriptions on meaningful icons, `null` on decorative ones; 48dp targets; error text
  announced (live region); labels, not placeholders, on fields.

**Security**
- No tokens in logs (sanitize the `Authorization` header); cleartext only in a debug network config.
