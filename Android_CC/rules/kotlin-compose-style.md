# Kotlin and Compose style

- Kotlin official code style; trailing commas; expression bodies for one-liners.
- Prefer `sealed interface` for states/events/results; `data object` for singletons.
- Coroutines: `viewModelScope` in ViewModels, never `GlobalScope`; rethrow `CancellationException` in catch-alls.
- Flows to UI: `collectAsStateWithLifecycle()`.
- **Material 3 family by default; Material 2 only when explicitly requested.**
  - `androidx.compose.material3:material3`: core components (Scaffold, TopAppBar, Button, TextField, dialogs, pull-to-refresh, …).
  - `androidx.compose.material3.adaptive:*` and `material3-adaptive-navigation-suite`: window-size-aware layouts
    (`NavigationSuiteScaffold`, list-detail and supporting-pane scaffolds, `currentWindowAdaptiveInfo()`). See the `adaptive` skill.
  - **Material 3 Expressive** (in `material3`, opt-in via `@OptIn(ExperimentalMaterial3ExpressiveApi::class)` where required):
    expressive theme, motion and components when the design calls for them. Prefer them for new UI once the
    project's material3 version provides them; keep usage behind the opt-in and consistent across screens.
    Verify availability by compiling: in material3 1.4.0 (Compose BOM 2026.09.00) the Expressive APIs are not public
    (`ExperimentalMaterial3ExpressiveApi` is internal), so fall back to standard Material 3 components there.
  - Never use `androidx.compose.material` (Material 2) components or theme unless the user explicitly asks.
    The only allowed `androidx.compose.material` artifacts are the icon libraries (`material-icons-core`/`-extended`):
    plain vectors rendered through Material 3's `Icon`.
  - Check: `grep -r "import androidx.compose.material\." | grep -v material.icons` must be empty.
  - Mixing Material 2 and Material 3 components in one screen is a review failure: different theming, ripple and color systems.
- Compose:
  - `Modifier` is the first optional parameter; previews use `@PreviewLightDark`.
  - All user-visible text in `strings.xml` (no hard-coded strings in composables).
  - Resolve `stringResource` outside non-composable builder lambdas (`navigationSuiteItems {}`, `LazyListScope`).
  - Hoist state; `rememberSaveable` for UI-only state that must survive recreation.
  - Every new screen follows the `edge-to-edge` skill (insets, IME, system bars).
- Comments explain *why*, not what; match the surrounding comment density.
