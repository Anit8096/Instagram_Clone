# Android_CC: Claude Code setup for Kotlin, Android and Ktor

Agents, skills, commands, rules and hooks for building Android apps with Kotlin, Jetpack,
Jetpack Compose and a Ktor backend. Content is written from hands-on development and grows as
new patterns, fixes and skills prove useful.

> Status: in development, not installed anywhere yet.

## Layout

```
Android_CC/
├── .claude-plugin/     plugin.json + marketplace.json (installable as a Claude Code plugin)
├── agents/             subagents for Android/Kotlin/Ktor work
├── skills/             workflows: Android CLI skills in use + skills written from experience
├── commands/           slash commands
├── rules/              always-follow guidelines (for ~/.claude/rules/)
├── hooks/hooks.json    hook wiring (SessionStart, PreToolUse)
├── scripts/hooks/      Node.js hook implementations
├── contexts/           mode prompts (implementation, code review, device debugging)
└── examples/           project CLAUDE.md template
```

## Install (when ready)

As a plugin, from Claude Code:
```
/plugin marketplace add <path-to>/Android_CC
/plugin install android-cc@android-cc
```
Or copy the folders into `~/.claude/` (`agents/`, `skills/`, `commands/`, `rules/`), and merge
`hooks/hooks.json` into `~/.claude/settings.json` with `${CLAUDE_PLUGIN_ROOT}` replaced by this folder's path.
Hooks need Node.js on PATH.

## Inventory

### Agents
| Agent | Purpose |
|---|---|
| `android-planner` | Requirements interview → spec, assumptions, risks, milestone plan |
| `android-architect` | Package, data-flow, DI and navigation design before coding |
| `gradle-build-resolver` | Gradle sync/build/compile failures (AGP 9 built-in Kotlin, version mismatches) |
| `compose-reviewer` | Review Compose changes: state, insets/IME, Navigation 3, accessibility, security |
| `android-test-engineer` | ViewModel, Ktor MockEngine, Robolectric Compose, navigation and DI-graph tests |
| `device-journey-runner` | Run journey XML on an emulator via the Android CLI and report |
| `dependency-scout` | Latest stable versions and Kotlin/AGP/JDK compatibility for new dependencies |
| `ktor-backend-engineer` | Ktor + Exposed + Flyway + JWT backend with Testcontainers |

### Skills
| Skill | Origin |
|---|---|
| `android-cli` | Android CLI skill (`android skills add android-cli`) |
| `navigation-3` | Android CLI skill |
| `edge-to-edge` | Android CLI skill |
| `testing-setup` | Android CLI skill (`android skills add testing-setup`) |
| `android-project-interview` | Written from experience: requirements interview for Android projects |
| `agp9-kotlin-toolchain` | Written from experience: Kotlin upgrades under AGP 9 built-in Kotlin, root-plugin sync failure |
| `jwt-auth-ktor-android` | Written from experience: JWT + rotating refresh tokens across Ktor server and Android client |
| `android-device-journeys` | Written from experience: journey tests on emulators, local-backend networking |
| `media-upload-pipeline` | Written from experience: Photo Picker → compression → Room draft → WorkManager upload; Ktor image processing and serving |
| `offline-first-feed` | Written from experience: Paging 3 RemoteMediator + Room 3 cache, offline banner, refresh signals, schema export + AutoMigration |
| `offline-action-queue` | Written from experience: Room-persisted actions, optimistic UI, ordered WorkManager delivery, collapse/reject/merge rules, idempotent Ktor endpoints |
| `realtime-websocket-chat` | Written from experience: Ktor WebSocket push with a sessions registry, typed events, foreground-only Android socket, idempotent sends, seen receipts |
| `release-hardening-android-ktor` | Written from experience: account deletion with 403 re-auth and counter fixes, idempotent seed data through real services, Android + Ktor CI, a11y/dark/font-scale pass, showcase README |
| `push-notifications-fcm` | Written from experience: transactional activity rows with schema-level dedup, socket-first delivery with FCM only for offline users, optional Firebase, channels, contextual permission, custom-scheme deep links into Nav3 back stacks |

Android CLI skills are copies of the versions installed by `android skills`. Their `SKILL.md`
refers to the publisher's license terms. Refresh with `android skills update`. Only skills actually
used in development are included.

### Commands
| Command | Does |
|---|---|
| `/android-plan <idea>` | Interview → spec → implementation plan |
| `/milestone [id]` | Implement a plan milestone end to end (server → client → tests → journey) |
| `/gradle-fix [error]` | Fix a Gradle/compile failure with the smallest change |
| `/android-check [quick\|full\|journey]` | Compile, unit tests, lint, optional device journey |
| `/journey <file>` | Evaluate a journey XML on an emulator and write results |
| `/deps-check <libs>` | Versions and coordinates for new dependencies |
| `/compose-review [files]` | Review Compose/Android changes |
| `/kit-sync` | Add newly used skills and lessons to this kit |

### Rules
`android-architecture`, `kotlin-compose-style`, `gradle-and-dependencies`, `android-testing`,
`android-security`, `android-tooling`, `subagent-routing`.

### Hooks
| Hook | Effect |
|---|---|
| SessionStart → `session-start.js` | Prints android CLI path, running devices, JDK and Docker status |
| PreToolUse(Bash/PowerShell) → `guard-dependency-internals.js` | Blocks `javap`/`unzip -l` and reads of `~/.gradle/caches` or generated sources |

## Maintenance
- A skill used even once in real development gets copied into `skills/`.
- New build fixes, device pitfalls and patterns go into the matching agent, skill or rule.
- Bump `version` in `.claude-plugin/plugin.json` when behaviour changes.
