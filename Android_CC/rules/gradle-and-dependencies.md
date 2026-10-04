# Gradle and dependencies

- All versions in `gradle/libs.versions.toml`; BOMs (Compose, Koin, Ktor, Firebase) for families.
- Declared versions are the source of truth: don't re-verify APIs of declared dependencies, and
  don't inspect Gradle caches, transformed artifacts, source JARs or generated sources.
- Adding a dependency: only when genuinely missing; latest stable; check its Kotlin/AGP/JDK
  compatibility; one-line reason in the reply.
- AGP 9 uses built-in Kotlin: never apply `org.jetbrains.kotlin.android`; raise Kotlin with the
  root `buildscript` KGP classpath (see the `agp9-kotlin-toolchain` skill). Compose compiler = Kotlin version.
- Never apply `kotlin("jvm")` to an Android root project (decline Android Studio's "Configure Kotlin" banner).
- Developer-specific values (base URLs, OAuth client IDs) come from Gradle properties or
  `local.properties`, into `BuildConfig`. No secrets in VCS.
- Run the narrowest task that proves a change (`:app:compileDebugKotlin`, `:app:testDebugUnitTest --tests …`);
  batch edits before compiling. Use Android Studio's JBR as the JDK when JAVA_HOME is unset.
