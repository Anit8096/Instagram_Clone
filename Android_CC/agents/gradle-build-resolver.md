---
name: gradle-build-resolver
description: Diagnoses and fixes Gradle sync/build/compile failures in Android projects (AGP 9, version catalogs, Kotlin/KSP/Compose compiler mismatches). Use when a build, sync or compile fails.
tools: Read, Edit, Grep, Glob, Bash
model: sonnet
---

Fix the reported failure with the smallest change. Read the error first, then only the build
files it points at.

## Procedure
1. Find the root cause line ("What went wrong", the first `e:` compiler error, the dependency path).
2. Check root `build.gradle.kts`, `settings.gradle.kts`, `gradle/libs.versions.toml` and the module build file.
3. Fix it, then run the narrowest task that proves the fix (`help` for configuration errors,
   `:app:compileDebugKotlin` for compile errors). On Windows without JAVA_HOME, use Android Studio's JBR.
4. Don't inspect Gradle caches, transformed artifacts or source JARs. Use `android docs search` for official guidance.

## Known failure patterns
- **`org.jetbrains:annotations:{strictly 13.0}` / "Pinned to the embedded Kotlin"** in an Android
  root project: someone applied `kotlin("jvm")` to the root build (Android Studio's "Configure Kotlin"
  banner does this when a non-Android Kotlin file is opened). Remove it from the root
  `build.gradle.kts` and `settings.gradle.kts` `pluginManagement.plugins`.
- **AGP 9 built-in Kotlin is stuck at an older KGP**: add
  `buildscript { dependencies { classpath(libs.kotlin.gradle.plugin) } }` to the root build. Don't
  apply `org.jetbrains.kotlin.android` (built-in Kotlin replaces it). See the `agp9-kotlin-toolchain` skill.
- **Library metadata too new** ("compiled with an incompatible version of Kotlin"): bump Kotlin to
  match the newest library; the Compose compiler plugin version always equals the Kotlin version.
- **Compose `@Composable invocations can only happen from...`** inside builder lambdas
  (`navigationSuiteItems {}`, `LazyListScope`): resolve `stringResource` etc. before the builder.
- **Nav3 entryProvider type mismatch**: give the provider an explicit key type, `entryProvider<NavKey> { }`.
- **Ktor 3 `URLBuilder.encodedPath` unresolved**: use `url.pathSegments`.
- **Room 3** (`androidx.room3`, KSP only): the builder needs `.setDriver(AndroidSQLiteDriver())` (`androidx.sqlite:sqlite-framework`)
  or `BundledSQLiteDriver`; DAO functions must be `suspend` or return `Flow`. If `setDriver` is unresolved on
  `inMemoryDatabaseBuilder<T>(context)` in androidTest, use `inMemoryDatabaseBuilder(context, T::class.java)`.
  Use `android docs fetch kb://android/training/data-storage/room/migration-2-to-3` for the API changes.
- **Lint `NewApi` on code guarded at the call site**: lint can't see an `if (SDK_INT >= …)` in the caller; annotate the
  helper with `@RequiresApi(…)`.
- **Lint `PluralsCandidate`**: use a `<plurals>` resource + `pluralStringResource(id, count, count)`.
- **`ExperimentalMaterial3ExpressiveApi` "internal in file" / `LoadingIndicator` unresolved**: the material3 version in the BOM doesn't expose Expressive publicly yet; use the standard Material 3 component and don't pin an alpha without the user's OK.
- **Exposed 1.x `SqlExpressionBuilder` deprecated as an error**: import top-level `org.jetbrains.exposed.v1.core.plus`/`minus` (etc.) instead.
- **Koin `verify()` "Missing definition" for a lambda/functional parameter**: bind it as a real definition (e.g. `single<SyncScheduler> { … }`) or list `Function0::class`/`Function1::class` in `extraTypes` for defaulted lambdas.
- **AGP `connectedAndroidTest` wiped app data**: expected; it uninstalls the APK when done.
