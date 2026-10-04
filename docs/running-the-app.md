# Running the Android app

## 1. Start the backend

```sh
cp .env.example .env      # once; fill in DATABASE_PASSWORD and JWT_SECRET
docker compose up --build -d
```

## 2. Start an emulator (Android CLI)

```sh
android emulator create medium_phone   # once
android emulator start medium_phone
```

If the emulator exits during boot (seen with a very new NVIDIA driver), start it with the software renderer:

```sh
"$ANDROID_SDK/emulator/emulator" @medium_phone -gpu swiftshader_indirect
```

## 3. Point the app at the backend

The default base URL is `http://10.0.2.2:8080`, the emulator's alias for the host. If sign-in shows
"Can't reach the server" (the emulator NAT can't reach Docker Desktop's port on some machines), tunnel
over adb instead:

```sh
adb reverse tcp:8080 tcp:8080
./gradlew :app:assembleDebug -Pinsta.apiBaseUrl=http://localhost:8080
```

To make it permanent, put `insta.apiBaseUrl=http://localhost:8080` in `~/.gradle/gradle.properties`.
`adb reverse` has to be re-run after each emulator restart.

## 4. Install and launch

```sh
android run --apks=app/build/outputs/apk/debug/app-debug.apk
```

## Test photos on the emulator

The photo picker needs at least one image in the gallery:

```sh
adb shell screencap -p /sdcard/Pictures/test_photo.png
adb shell am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d file:///sdcard/Pictures/test_photo.png
```

On recent Android versions the picker asks you to tap **Done** after selecting a photo.

## Demo data

```sh
docker compose exec server java -cp "/app/lib/*" com.android.insta.server.seed.SeedKt
```

Creates six accounts (`maya.travels`, `leo.bakes`, `ana.draws`, `sam.runs`, `noor.codes`, `kai.garden`, all with
password `demo-password`), each with an avatar and three posts, plus follows, likes, comments and a conversation
between maya and leo. Running it again does nothing. Outside Docker: `cd server && ./gradlew seed` with the same env
vars as the server.

## Google sign-in (optional)

The "Continue with Google" button only appears when an OAuth **Web** client ID is configured:

1. In Google Cloud Console, create an OAuth client of type *Web application*, plus an *Android*
   client for `com.android.insta` with your debug keystore's SHA-1 (`./gradlew :app:signingReport`).
2. App: `insta.googleServerClientId=<web client id>` (Gradle property or `local.properties`).
3. Server: `GOOGLE_CLIENT_IDS=<web client id>` in `.env`, then `docker compose up -d server`.

## Push notifications (optional, Firebase)

Without Firebase everything except system push works: the Activity tab, its badge and live updates run over the
WebSocket. To get pushes while the app is in the background:

1. Create a Firebase project and add an Android app with package `com.android.insta`.
2. Download `google-services.json` into `app/` (gitignored). The build applies the google-services plugin only when
   this file exists.
3. *Project settings → Service accounts → Generate new private key*. Save the JSON into `secrets/` at the repo root
   (gitignored; mounted read-only at `/run/secrets/insta`), e.g. `secrets/firebase-adminsdk.json`.
4. In `.env`: `FIREBASE_CREDENTIALS_FILE=/run/secrets/insta/firebase-adminsdk.json`, then `docker compose up -d server`.
5. Use an emulator image **with Google Play** and allow notifications from the Activity tab's card.

The server only pushes when the recipient has no open socket, so put the app in the background (Home) to see one.

Deep links can be tried without Firebase:

```sh
adb shell am start -a android.intent.action.VIEW -d insta://chat/<username>
adb shell am start -a android.intent.action.VIEW -d insta://user/<username>
adb shell am start -a android.intent.action.VIEW -d insta://post/<post-id>
adb shell am start -a android.intent.action.VIEW -d insta://activity
```

## Tests

```sh
./gradlew :app:testDebugUnitTest      # ViewModels, network/auth refresh, navigation, Koin graph, Robolectric UI
./gradlew :app:lintDebug
cd server && ./gradlew test           # needs Docker running for the Testcontainers tests
```

Manual end-to-end check: `journeys/m2-auth.xml` (results in `journeys/results/`).
