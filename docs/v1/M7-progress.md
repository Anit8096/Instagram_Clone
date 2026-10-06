# M7 progress — Notifications + FCM

Resumable checklist. Branch `worktree-m7-notifications`. **Status: M7 complete.**

## Server
- [x] V3 migration: one like/follow notification per actor (partial unique indexes), unread index
- [x] Notifications module: record in the same transaction as like/comment/follow; delete on unlike/unfollow
- [x] `GET /notifications`, `GET /notifications/unread-count`, `POST /notifications/read`
- [x] `PUT /me/devices/{token}`, `DELETE /me/devices/{token}`
- [x] `PushSender`: `NoopPushSender` (no credentials) and `FcmPushSender` (Firebase Admin); push only when offline; prune dead tokens
- [x] WebSocket events `notification.new` (+ unread count) and `badge`
- [x] DMs push to offline recipients (no activity-feed row)
- [x] Tests (routes + push decisions), OpenAPI, docker-compose secret mount

## App
- [x] Firebase optional: google-services plugin applied only when `app/google-services.json` exists
- [x] Notifications data + Activity tab (paged list, mark read, badge)
- [x] `InstaMessagingService`: token upload on rotation/login, data messages → system notification
- [x] Notification channels; POST_NOTIFICATIONS asked contextually (API 33+)
- [x] Deep links `insta://post/{id}`, `insta://user/{username}`, `insta://chat/{username}`, `insta://activity` → back stack
- [x] Unit tests (84 total), lint (0 errors)
- [x] Journey m7-notifications on the emulator (10/10)

## Docs / kit
- [x] IMPLEMENTATION_PLAN status + "Changes made during M7"; Firebase setup steps in running-the-app.md
- [x] Android_CC skill update
