---
description: Review Compose/Android changes for state, insets, navigation, accessibility and security bugs
argument-hint: [files | diff range]
---

Review $ARGUMENTS (default: uncommitted changes, or the files changed in this session) using the
`compose-reviewer` agent checklist. Load the `edge-to-edge` skill for inset questions and
`navigation-3` for navigation ones. Report only verified problems, most severe first, with
file:line, the failure scenario and the fix.
