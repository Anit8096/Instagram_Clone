---
name: offline-action-queue
description: Offline-capable user actions (likes, comments, follows, messages) for a Compose app with a Ktor backend. Room-persisted queue, optimistic UI, ordered delivery by a WorkManager worker, collapse of opposite toggles, retry vs reject rules, re-applying queued state over server refreshes, and idempotent server endpoints. Use when actions must work without a connection.
---

# Offline action queue

## Server prerequisites (Ktor)
Every queued action maps to an **idempotent** endpoint:
- toggles: `PUT /posts/{id}/like` / `DELETE /posts/{id}/like` (insert-ignore / delete; adjust counters only when a row changed, in the same transaction);
- creates: `PUT /posts/{id}/comments/{clientId}` (201 first time, 200 + same body on replay, 409 if the id belongs to someone else);
- deletes: return 204 even if already gone.

## Room
`pending_actions(id PK = client UUID, type, targetId, body?, createdAt, state = pending|failed, error?)`.
The id doubles as the server id for creates, so a replay after a crash can't duplicate.

## Queue (repository)
- **Enqueue** = one Room write transaction: insert the action **and** apply the optimistic change to cached rows
  (e.g. `UPDATE feed SET likedByMe = :liked, likeCount = MAX(likeCount + :delta, 0)`), then schedule the worker.
- **Collapse toggles**: delete unsent like/unlike rows for the target before inserting the new one; only the final state is sent.
- **Drain** (`sync()`): loop `nextPending()` ordered by `createdAt`:
  - success → delete the row (emit a "synced" signal for screens showing the target);
  - `Network` / 5xx / 408 / 429 / unexpected → stop and return Retry (preserves order);
  - other 4xx → reject: toggles are deleted and their optimistic change reverted; creates are marked `failed`
    with the server message (UI offers Retry / Discard).
- **Merge over refresh**: when a list refresh writes server rows, re-apply unsent toggles (`pendingLikeOverrides()`
  → adjust `likedByMe`/count) so the UI never flickers back.
- **Logout**: cancel the work and delete the queue with the other per-account data.

## WorkManager
`CoroutineWorker` calling `queue.sync()`; `Result.retry()` on Retry. Network constraint, exponential backoff (10 s base).
Schedule with `enqueueUniqueWork(name, ExistingWorkPolicy.APPEND_OR_REPLACE, …)` so actions added mid-sync get a follow-up run.
Inject the queue via `workerOf(::Worker)` (Koin) and bind the scheduler as an interface so tests can replace it.

## UI
- Toggle buttons read the cached row (already updated), so feedback is instant.
- Screens with their own copy (detail screens) update local state and enqueue.
- Queued creates render inline with "Sending…" or "Couldn't send · Retry · Discard".
- Screens collect "synced"/"changed" signals in the ViewModel and reload.

## Tests
Real in-memory Room (Robolectric, `AndroidSQLiteDriver`) + Ktor `MockEngine`:
optimistic update and collapse (3 taps → 1 request), FIFO delivery, offline → Retry with rows kept,
4xx → rollback/failed, retry after failure, merge helper. On a device: stop the backend, act, confirm "Sending…",
start the backend, and watch the server log for in-order delivery.
