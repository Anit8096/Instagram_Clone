---
description: Fix a Gradle sync/build/compile failure (paste the error or I'll reproduce it)
argument-hint: [pasted error | task to run]
---

Use the `gradle-build-resolver` agent approach on: $ARGUMENTS

- If an error is pasted, start from its root cause; otherwise run the failing task (default `:app:compileDebugKotlin`).
- Check the known patterns first (root `kotlin("jvm")` from the IDE banner, AGP 9 KGP pinning,
  Kotlin/library metadata mismatch, composable calls in builder lambdas).
- Make the smallest fix, re-run the narrowest task, and report the cause in one line.
