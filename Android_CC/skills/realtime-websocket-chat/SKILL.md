---
name: realtime-websocket-chat
description: Real-time 1:1 chat for a Compose app with a Ktor backend. Authenticated WebSocket push (Ktor server sessions registry, typed JSON events), REST for history/send/read, idempotent sends through an offline action queue, a foreground-only Android socket with reconnect backoff, and seen receipts. Use for DMs, live notifications or any server push.
---

# Real-time chat over WebSockets (Ktor ↔ Android)

## Server (Ktor)
- `install(WebSockets) { pingPeriod = 15.seconds; timeout = 30.seconds }`; mount `webSocket("/ws")` **inside `authenticate`**,
  so the handshake uses the same bearer token as REST and `call.principal` works in the handler.
- `ConnectionRegistry`: `ConcurrentHashMap<UserId, MutableSet<WebSocketSession>>`; add on connect, remove in `finally`;
  `send(userIds, event)` wraps each `session.send` in `runCatching` (dead sockets clean themselves up).
- Events: a `@Serializable sealed interface` with `@SerialName("message.new")`, `@SerialName("message.read")` etc.
  (kotlinx `type` discriminator); share the exact names with the client.
- REST: get-or-create 1:1 conversation (store `user_a < user_b`; UUID string order equals Postgres byte order),
  inbox with last message and unread count, history newest-first with a keyset cursor,
  **idempotent send** `PUT /conversations/{id}/messages/{clientId}` (push only when newly created),
  `POST /conversations/{id}/read` (upsert last-read time, push a read event).
- Membership check on every conversation endpoint (404 for outsiders).
- Test: `createClient { install(io.ktor.client.plugins.websocket.WebSockets) }` inside `testApplication`, open the
  socket with `request = { bearerAuth(token) }`, trigger a send over REST, `withTimeout { incoming.receive() }`.

## Android client
- The shared Ktor client **must** `install(WebSockets)`, or `client.webSocket(...)` throws on every attempt
  ("Plugin WebSockets is not installed"). Add a unit test asserting `client.pluginOrNull(WebSockets) != null`.
- WS URL: the base URL with `http` → `ws`. The bearer Auth plugin adds the token to the handshake.
- Lifecycle: `combine(foreground, signedIn).distinctUntilChanged().collectLatest { if (it) connectLoop() }`, where
  foreground comes from `ProcessLifecycleOwner.repeatOnLifecycle(STARTED)` (`lifecycle-process`). `collectLatest`
  cancels the socket when the app backgrounds or the user signs out. Backoff 1 s → 30 s; reset after connecting.
- Fan events out through a `SharedFlow`. The thread screen appends `message.new` for its conversation (dedupe by id)
  and marks read when an incoming message arrives while it's open; the inbox reloads on any event.
- Sending goes through the offline action queue (client UUID = message id), shown at once as "Sending…". When a queued
  message is delivered, emit a signal so the thread reloads (covers the socket being down).
- Seen receipt: show under the latest own message when `peerLastReadAt >= message.createdAt`; update on `message.read`
  events from the peer.

## Verifying on a device
Drive the other participant from the harness through the API (send, mark read) while the thread is open, and assert
the UI updates within a few seconds **without any tap**. That's the only test that proves the socket path end to end.
Check logcat for the client's "socket closed" log when it doesn't.
