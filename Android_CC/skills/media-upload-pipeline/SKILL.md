---
name: media-upload-pipeline
description: Reliable photo upload for a Compose app with a Ktor backend. Photo Picker → client JPEG compression (EXIF-correct) → Room draft queue → WorkManager upload with retry → idempotent create, plus server-side validation, EXIF stripping, aspect clamping, resizing and cache-friendly serving. Use when building image posting, avatars or any user media upload.
---

# Media upload pipeline (Android client + Ktor server)

## Server (Ktor)
1. `POST /media?kind=…` multipart (field `file`): `receiveMultipart(formFieldLimit = 2*limit)`, read the part
   with `provider().readRemaining(limit + 1).readByteArray()`, and reject anything over the limit with 413.
2. Detect the format from **magic bytes** (JPEG `FF D8 FF`, PNG `89 50 4E 47 0D 0A 1A 0A`, WebP `RIFF....WEBP`); never trust Content-Type.
3. Read only the image header first (ImageIO reader `getWidth/getHeight`) and reject huge dimensions before decoding (decompression bombs).
4. Decode with EXIF orientation applied (Thumbnailator `useExifOrientation(true)`; TwelveMonkeys ImageIO plugins for
   robust JPEG and WebP input), flatten alpha onto white, then:
   - **clamp the aspect** to 4:5…1.91:1 with a center crop (tall phone screenshots otherwise push UI below the fold),
   - display image fits 1080×1350 (never upscale), grid thumbnail is a 320×320 center crop,
   - re-encode as JPEG, which strips all metadata (GPS).
5. Store files behind a `MediaStorage` interface (local disk, write-temp-then-atomic-move, path-traversal check); the DB row holds relative keys.
6. Serve `GET /media/{id}/{variant}` with `Cache-Control: public, max-age=31536000, immutable` and an ETag (answer
   `If-None-Match` with 304). Files never change; a new upload gets a new id.
7. Create the owning entity **idempotently**: `PUT /posts/{clientId}` returns 201 the first time and 200 with the same
   body on retry; 409 if another user owns that id. A unique index on `media_id` stops one upload backing two posts.
8. Delete DB rows in a transaction, then delete files **after commit** (return the file keys from the transaction).

## Android client
1. **Photo Picker**: `rememberLauncherForActivityResult(PickVisualMedia())` with `PickVisualMedia.ImageOnly`. No storage permission.
2. **Compress immediately** into an app-owned file (the picker's URI grant doesn't survive process death):
   API 28+ `ImageDecoder` (applies EXIF, `setTargetSize`, `ALLOCATOR_SOFTWARE`, mark the function `@RequiresApi(P)`);
   API ≤27 `BitmapFactory` with `inSampleSize` + `androidx.exifinterface` rotation. Max ~2048 px, JPEG q90.
3. **Draft row** (Room): `id` = the final client-generated entity id, `imagePath`, `caption`, `state`, `mediaId?`, `error?`.
4. **Worker** (`CoroutineWorker`, network constraint, exponential backoff, unique work per draft with `KEEP`):
   - no `mediaId` → upload, then save `mediaId` on the draft (a retry skips re-uploading),
   - `PUT /posts/{draftId}`; on success delete the draft and file and emit a `postsChanged` signal,
   - network / 5xx / 408 / 429 → `Result.retry()` up to N attempts; other 4xx → mark FAILED with the server message,
   - server says the media is invalid → clear `mediaId` and retry (re-upload).
   Regular work is enough; expedited work needs `getForegroundInfo` (a notification) below API 31.
5. **UI**: a banner over the content observing drafts ("Posting…" / "Couldn't post · Retry · Discard").
6. **Logout and session expiry**: cancel the work and delete drafts, so they never post under another account.
7. **Images**: Coil 3 `KtorNetworkFetcherFactory(httpClient = { client })` and a sized disk cache. Resolve
   server-relative URLs to absolute ones in the data layer (Coil treats scheme-less strings as file paths).
   Clamp the display aspect ratio to the same range the server uses.

## Gotchas
- Don't set `Content-Type: application/json` in Ktor's `defaultRequest`: it breaks multipart. Set it per JSON request (`jsonBody(...)` helper).
- Koin + WorkManager: `workManagerFactory()` in `startKoin`, `workerOf(::MyWorker)`, and remove `androidx.work.WorkManagerInitializer` from the startup provider in the manifest (`tools:node="remove"`).
- Test the worker with `work-testing`'s `TestListenableWorkerBuilder` and a custom `WorkerFactory`; test the repository with Ktor `MockEngine` and a fake DAO.
