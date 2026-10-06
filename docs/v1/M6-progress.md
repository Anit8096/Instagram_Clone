# M6 (real-time DMs): progress checklist

Resume with: "continue M6 from docs/M6-progress.md".

- [x] Server: tables mapped (Conversations, Messages, ConversationReads)
- [x] Server: ChatService: get-or-create conversation (user_a < user_b), inbox with last message + unread, history (newest first, cursor), idempotent `PUT /conversations/{id}/messages/{clientId}`, `POST /conversations/{id}/read`
- [x] Server: WebSocket `/api/v1/ws` (inside `authenticate`), ConnectionRegistry userId → sessions, events `message.new`, `message.read`
- [x] Server tests (REST + WebSocket delivery), 42 passing
- [x] App: ChatApi, RealtimeClient (Ktor WS, foreground-only via ProcessLifecycleOwner, reconnect with backoff)
- [x] App: ActionQueue type `message` (targetId = conversationId) + pendingMessages flow
- [x] App: Inbox screen (Feed top bar → Messages), Thread screen (seen receipt, "Sending…"), Profile "Message" button
- [x] App tests (77), journey `journeys/m6-dm.xml`, plan Status, Android_CC skill
- Status: **M6 complete** (see docs/IMPLEMENTATION_PLAN.md).
