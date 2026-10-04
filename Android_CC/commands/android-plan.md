---
description: Interview me about a new Android app/feature, then produce spec, assumptions, risks and a milestone plan
argument-hint: [one-line idea]
---

Run the `android-project-interview` skill for: $ARGUMENTS

- Read the existing project first (Gradle files, `libs.versions.toml`, app sources) so questions build on what exists.
- Ask in batches with AskUserQuestion; reflect back after each batch; flag conflicts; label ASSUMPTIONS.
- When aligned, write the spec, then ask whether to turn it into `docs/IMPLEMENTATION_PLAN.md`.
- Use the `dependency-scout` agent for the version table.
