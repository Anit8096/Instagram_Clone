---
name: android-project-interview
description: Structured requirements interview for a new Android app or big feature. Asks batched, high-leverage questions, pushes back on vague answers, tracks assumptions and risks, then produces a spec and a milestone plan. Use before writing any code for a new project.
---

# Android project interview

Goal: full alignment before any code or final plan. Interview; don't build.

## Rules
1. Ask in batches of 3–6 questions grouped by topic (the AskUserQuestion tool allows up to 4
   per call; offer a "(Recommended)" option first).
2. Most ambiguous, highest-cost-if-wrong questions first.
3. After each batch, reflect back what you understood in 1–2 sentences, then continue.
4. Vague answer → 2–3 concrete options with a recommended default and the trade-off.
5. Flag answers that conflict with earlier ones, and resolve them before moving on.
6. "I don't know" → propose a default labelled **ASSUMPTION** and move on.
7. Keep the user's explicitly named stack; never silently swap tools.
8. Stay version-accurate: check current versions (dependency-scout agent / `android docs`).
9. If the user rejects a question batch, ask what they want to clarify; their reply may change the question.

## Topic order (skip what doesn't apply)
1. Product: one-line pitch, goal (portfolio / small launch / scale), platforms.
2. Backend fork: BaaS (Firebase/Supabase) vs custom (which stack) vs none.
3. v1 scope: in/out lists; check that dependent features stay coherent.
4. Auth: method, token storage, Google sign-in (needs a Cloud project).
5. Deployment: local only (emulator→host networking) vs hosted.
6. Android architecture: modules, DI, network client, navigation, image loading, persistence.
7. Data: entities, privacy model, feed construction, media processing.
8. Offline: read cache vs action queue (needs client UUIDs + idempotent endpoints), drafts, preferences store.
9. Quality bar: tests, CI, lint; safety features (delete account is a Play policy requirement).
10. Notifications/realtime: WebSocket vs polling; FCM.

## Deliverable (when guesswork is gone)
- Spec: product, users, scope in/out, core flows, data model, architecture, integrations.
- ASSUMPTIONS (everything not firmly decided).
- OPEN RISKS.
- Ask: "Ready for me to turn this into an implementation plan?"
- Implementation plan in `docs/IMPLEMENTATION_PLAN.md`: verified version table, repo layout,
  milestones (each demoable and tested), a secrets checklist, and per-milestone verification steps.
