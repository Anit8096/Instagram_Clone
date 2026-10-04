---
name: agp9-kotlin-toolchain
description: Upgrade or repair the Kotlin toolchain in an AGP 9 project that uses built-in Kotlin (no kotlin-android plugin): bump KGP, Compose compiler, serialization and KSP, and fix the classic "annotations strictly 13.0" sync failure. Use when changing Kotlin versions or when Gradle sync breaks after Kotlin changes.
---

# AGP 9 + Kotlin toolchain

## How Kotlin is chosen under AGP 9
AGP 9 enables built-in Kotlin and has a runtime dependency on a fixed KGP (2.2.10 for AGP 9.0).
To use a newer Kotlin (source: AGP 9.0 release notes, `android docs fetch kb://android/build/releases/agp-9-0-0-release-notes`):

```kotlin
// root build.gradle.kts
buildscript {
    dependencies {
        classpath(libs.kotlin.gradle.plugin)   // org.jetbrains.kotlin:kotlin-gradle-plugin:<kotlin>
    }
}
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false        // same version as Kotlin
    alias(libs.plugins.kotlin.serialization) apply false  // same version as Kotlin
}
```

- Don't apply `org.jetbrains.kotlin.android`; built-in Kotlin replaces it.
- KSP ≥ 2.3.0 is no longer tied to the Kotlin version; raise it via `classpath("com.google.devtools.ksp:symbol-processing-gradle-plugin:<v>")` if needed.
- Verify: `./gradlew buildEnvironment | grep kotlin-gradle-plugin` should show `2.2.10 -> <new>`.

## Choosing the Kotlin version
Use the newest Kotlin any dependency was compiled with, or newer. A compiler reads library metadata
at most one minor release ahead. Example: Coil 3.6 (built with 2.4.x) breaks a 2.2 compiler.

## The "annotations {strictly 13.0}" sync failure
```
Could not resolve org.jetbrains:annotations:{strictly 13.0}
... because of the following reason: Pinned to the embedded Kotlin
```
Cause: `kotlin("jvm")` was applied to the Android root project, often inserted by Android Studio's
"Kotlin not configured → Configure" banner after you open a Kotlin file outside the Android
modules (e.g. a sibling `server/` project). Fix: remove from the root
`build.gradle.kts` the `kotlin("jvm")` plugin, `dependencies { implementation(kotlin("stdlib-jdk8")) }`,
`repositories {}` and `kotlin { jvmToolchain(8) }`, and remove `kotlin("jvm") version "x"` from
`settings.gradle.kts` → `pluginManagement.plugins`.
Prevention: dismiss that banner; link sibling JVM projects as separate Gradle projects.

## Keeping a sibling JVM server separate
A Ktor server next to the app should be its own Gradle build (own `settings.gradle.kts`, wrapper and
catalog), not `include`d into the Android build, so their Kotlin/plugin classpaths never mix.
