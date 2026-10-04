---
name: push-notifications-fcm
description: In-app activity feed plus FCM push for a Compose app with a Ktor backend. Notification rows written in the same transaction as the event, dedup with partial unique indexes, socket-first delivery with push only for offline users, Firebase made optional on both sides, data-only messages, channels, POST_NOTIFICATIONS asked in context, and custom-scheme deep links into Navigation 3 back stacks. Use for any notification, badge or deep-link work.
---

# Notifications: activity feed, badge, FCM push, deep links

## Server (Ktor + Exposed)
- **Write in the event's transaction.** `recordNotification(recipient, actor, type, …)` is a plain function called inside the
  like/comment/follow transaction and returns the new id (or null). Deliver **after commit** with that id, never from
  inside the transaction (a rollback must not leave a sent push).
- **No self-notifications**: return null when `recipient == actor`.
- **Collapse spam with the schema**: partial unique indexes such as `UNIQUE (actor_id, post_id) WHERE type = 'like'`
  plus `insertIgnore` (`ON CONFLICT DO NOTHING` matches partial indexes too). Unlike/unfollow deletes the row; FKs with
  `ON DELETE CASCADE` remove rows for deleted comments and posts.
- **Socket first, push second**: after commit, send `notification.new { notification, unreadCount }` to the recipient's
  open sockets; push only if `registry.isOnline(recipient)` is false. Messages: the socket already carries
  `message.new`, so push only when offline, with no activity row (the inbox has its own unread counts).
- **Fire-and-forget pushes** on a service-owned `CoroutineScope(SupervisorJob() + Dispatchers.IO)`, closed by Koin
  `withOptions { onClose { it?.close() } }`. A slow push service must not delay the HTTP response.
- `PushSender` interface → `NoopPushSender` when no credentials are configured (the stack must run without Firebase)
  and `FcmPushSender` (Admin SDK). Initialise `FirebaseApp` under a **named** app and reuse it if it exists
  (`FirebaseApp.getApps()`), or a second init throws.
- Send with `messaging.sendEach(list of Message)` (one per token, ≤500 per call; the multicast builder is deprecated).
  Prune tokens whose error code is `UNREGISTERED` or `INVALID_ARGUMENT`.
- **Data-only messages** (`title`, `body`, `link`, `tag`, `type`) with Android priority HIGH: the app builds the
  notification, so channels, deep links and grouping stay in app code.
- Endpoints: `GET /notifications` (keyset cursor), `GET /notifications/unread-count`, `POST /notifications/read`
  (also push a `badge` event so other devices clear), `PUT/DELETE /me/devices/{token}` (upsert by token so a reused
  device moves to the new account).
- Credentials: mount a gitignored `./secrets` directory read-only and point an env var at the file; empty means Noop.
- Test with a recording `PushSender` that writes into a `Channel` and reports a magic token as dead. Await pushes with
  `withTimeout`, and assert "no push while a socket is open" with `withTimeoutOrNull(500)`.

## Android
- **Firebase optional at build time**:
  `if (file("google-services.json").exists()) apply(plugin = libs.plugins.google.services.get().pluginId)` with the
  plugin declared `apply false` at the root. Keep `firebase-messaging` (BOM) always on the classpath; guard runtime
  calls with `FirebaseApp.getApps(context).isNotEmpty()`.
- Token lifecycle: register on sign-in (session flow → `LoggedIn`) and on `onNewToken`, but only while signed in. On
  sign-out **delete the token locally** (`deleteToken()`). That works even when the session already expired
  server-side; the server prunes the dead token on its next send. Don't call the API from the expiry path.
- FCM is moving from registration tokens to Firebase Installation IDs (`register()` / `onRegistered()`). Both are
  co-supported; stay on tokens while the server addresses tokens, and keep the deprecation suppressed with a comment.
- Channels created at app start (`NotificationChannelCompat`): separate ones for messages (HIGH) and activity
  (DEFAULT), so users can mute one. Post with a **tag** (`post:{id}`, `chat:{user}`), so bursts replace one notification.
- Check `areNotificationsEnabled()` and, on API 33+, `POST_NOTIFICATIONS`; still catch `SecurityException` around `notify`.
- **Ask in context**: a dismissible card on the notifications screen with `rememberLauncherForActivityResult(RequestPermission())`,
  not a prompt at app start.
- Badge: an app-scoped holder bound to the session. Fetch the count when the socket (re)connects, update it from
  `notification.new` / `badge` events, and clear it when the screen marks everything read. To keep "new" highlighting,
  wait for the list refresh to finish
  (`snapshotFlow { loadState.refresh }.dropWhile { it !is Loading }.first { it !is Loading }`) before marking read.
- **Navigation-bar badges and accessibility**: M3 navigation items clear their icon's semantics when a label is shown,
  so a `Badge`'s content description is lost. Put the count on the item's `Modifier.semantics { stateDescription = … }`.

## Deep links (custom scheme → Navigation 3)
- Manifest: `launchMode="singleTop"` + `VIEW`/`DEFAULT` intent filter for the scheme. Notification `PendingIntent`:
  explicit `Intent(ACTION_VIEW, uri, context, MainActivity::class.java)`, `FLAG_IMMUTABLE`, and a request code per tag.
- Parse with a pure function (`java.net.URI`, regex-validated args) → `NavKey?`, so it's unit-testable without Android.
- Hand-off: the Activity submits into an app-scoped `PendingDeepLinks` (`StateFlow<NavKey?>`), in `onCreate` only when
  `savedInstanceState == null`, and in `onNewIntent`. The signed-in shell consumes it in a `LaunchedEffect`, so a
  link that arrives during splash or sign-in still opens.
- Synthetic back stack: a conversation opens as Home › Inbox › Thread. A second conversation link replaces the open
  thread, and re-opening the top screen is a no-op.
- Try links without any push setup: `adb shell am start -a android.intent.action.VIEW -d scheme://host/arg`
  (prefix `MSYS_NO_PATHCONV=1` in Git Bash).
