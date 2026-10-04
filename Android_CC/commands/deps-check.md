---
description: Find latest stable versions/coordinates for NEW dependencies and check Kotlin/AGP/JDK compatibility
argument-hint: <library names>
---

Use the `dependency-scout` agent for: $ARGUMENTS

Skip anything already declared in `gradle/libs.versions.toml`. Return the table (coordinates,
latest stable, notes), then add the approved entries to the catalog and build files with a
one-line reason each.
