---
name: offline-first-feed
description: Network-first paged feed with an offline Room cache in Compose. Paging 3 RemoteMediator over a Ktor cursor API, Room 3 PagingSource, cache replaced only on successful refresh, offline banner from LoadState.mediator, refresh signals that survive tab switches, and Room schema export with AutoMigration. Use for any feed/timeline/list that must work offline.
---

# Offline-first feed (Paging 3 + RemoteMediator + Room 3)

## Server contract
`GET /feed?cursor=&limit=` → `{ "items": [...], "nextCursor": "…" | null }`, keyset-paginated newest first.

## Room
- `@Entity feed_posts(postId PK, position INT, …)`: `position` preserves server order across pages; store **absolute** image URLs.
- `@Entity remote_keys(list PK, nextCursor)`: one row per list.
- DAO: `@DaoReturnTypeConverters(PagingSourceDaoReturnTypeConverter::class)` (artifact `androidx.room3:room3-paging`) and
  `@Query("SELECT * FROM feed_posts ORDER BY position") fun pagingSource(): PagingSource<Int, FeedPostEntity>`.
- Schema export: plugin `androidx.room3` + `room3 { schemaDirectory("$projectDir/schemas") }`, `exportSchema = true`.
  Export the current version **before** changing entities, then bump with `autoMigrations = [AutoMigration(from = N, to = N+1)]`
  for additive changes (new tables/columns). Commit `app/schemas/`.

## RemoteMediator
```kotlin
override suspend fun initialize() = InitializeAction.LAUNCH_INITIAL_REFRESH   // network first
override suspend fun load(loadType: LoadType, state: PagingState<Int, Entity>): MediatorResult {
    val cursor = when (loadType) {
        REFRESH -> null
        PREPEND -> return MediatorResult.Success(endOfPaginationReached = true)
        APPEND -> dao.key(KEY)?.nextCursor ?: return MediatorResult.Success(endOfPaginationReached = true)
    }
    return when (val r = api.feed(cursor, state.config.pageSize)) {
        is Failure -> MediatorResult.Error(AppErrorException(r.error))       // cache untouched → offline still works
        is Success -> {
            db.withWriteTransaction {                                         // androidx.room3.withWriteTransaction
                if (loadType == REFRESH) { dao.clearPosts(); dao.clearKeys() } // replace only after a successful fetch
                val start = dao.maxPosition() + 1
                dao.insertAll(r.value.items.mapIndexed { i, dto -> dto.toEntity(start + i) })
                dao.putKey(RemoteKeyEntity(KEY, r.value.nextCursor))
            }
            MediatorResult.Success(endOfPaginationReached = r.value.nextCursor == null)
        }
    }
}
```
`Pager(PagingConfig(pageSize = 20, enablePlaceholders = false), remoteMediator = …, pagingSourceFactory = dao::pagingSource)`,
then `.cachedIn(viewModelScope)`.

## UI states (LazyPagingItems)
- `itemCount == 0 && refresh is Loading` → full-screen progress.
- `itemCount == 0 && loadState.mediator?.refresh is Error` → error + Retry.
- `itemCount == 0 && refresh is NotLoading` → empty state with a call to action.
- Items present and `loadState.mediator?.refresh is Error` → **offline banner** ("Showing saved posts · Retry") above cached items.
- `PullToRefreshBox(isRefreshing = refresh is Loading && itemCount > 0, onRefresh = items::refresh)`.
- Footer for `loadState.append` Loading/Error. Keys via `items.itemKey { it.id }`.

## Refresh after local changes
Repositories emit `SharedFlow<Unit>` signals (post published, follow changed). **Collect them in the ViewModel** and set a
`needsRefresh: StateFlow<Boolean>`; the screen does `LaunchedEffect(needsRefresh) { if (it) { items.refresh(); vm.onRefreshHandled() } }`.
Collecting in a composable `LaunchedEffect` loses signals emitted while another tab is shown (the entry isn't composed).

## Network-only lists
For non-cached lists (explore grid, follower lists) use a small `CursorPagingSource` (key = cursor string,
`getRefreshKey = null`) that maps typed API errors to `LoadResult.Error`.

## Tests
- Mediator against a real in-memory Room DB under Robolectric (`AndroidSQLiteDriver`) + Ktor `MockEngine`:
  refresh order and cursor, append continues, failed refresh keeps cache, refresh replaces cache, initialize action.
- Read the cache through `pagingSource().load(LoadParams.Refresh(null, 100, false))`.
- ViewModel: a signal emitted with no UI attached sets `needsRefresh`.

## Verifying offline on an emulator
Airplane mode doesn't cut an `adb reverse` tunnel. Stop the backend instead (e.g. `docker compose stop server`), relaunch, and expect the cached feed plus the banner.
