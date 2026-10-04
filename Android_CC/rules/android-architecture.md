# Android architecture

- Single-activity Jetpack Compose app; MVVM + unidirectional data flow.
  - ViewModel exposes `StateFlow<UiState>` (immutable data class) and `fun onEvent(event: Event)`.
  - Composables are stateless `XContent(state, onEvent)` wrapped by `XScreen(viewModel = koinViewModel())`.
- Package by feature: `feature/<name>/data` (DTOs, API, repository) and `feature/<name>/ui`;
  shared code in `core/` (network, session, ui, database, datastore).
- Repositories return a typed result (`ApiResult<T>` / `AppError`), never raw exceptions to the UI.
- ViewModels never hold Android strings or Contexts: use a `UiMessage` (resource id or raw text).
- Navigation state is the single source of truth for screens; flow switches (auth ↔ main) follow
  observable session state, not imperative navigation calls.
- One HTTP client, configured in one factory function so tests exercise the real plugins.
- Platform objects (HTTP engine, DataStore, dispatcher, clock) get their own DI bindings so tests can replace them.
- Repositories expose explicit change signals (`SharedFlow<Unit>`) after successful writes; screens that show that
  data refresh on them.
- Work that must survive process death (uploads, sync) goes through WorkManager with a persisted Room row as the
  source of truth; the UI observes the rows, not the work.
- One HTTP client: set `Content-Type` per JSON request, not in `defaultRequest` (multipart needs its own).
- Follow the existing architecture in the project; don't introduce a second pattern for the same concern.
