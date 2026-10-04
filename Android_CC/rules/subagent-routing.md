# When to use which agent

| Situation | Agent |
|---|---|
| New app or big feature, requirements unclear | `android-planner` |
| Designing a feature's structure before coding | `android-architect` |
| Gradle sync/build/compile failure | `gradle-build-resolver` |
| Reviewing UI/Compose changes | `compose-reviewer` |
| Writing or fixing tests | `android-test-engineer` |
| Verifying a flow on an emulator | `device-journey-runner` |
| New dependency or toolchain versions | `dependency-scout` |
| Ktor/Postgres backend work | `ktor-backend-engineer` |

- Delegate noisy, self-contained work (version research, log trawls, journey runs) so the main context keeps only the findings.
- Don't spawn agents for small, well-understood edits.
- Treat an agent's report as data: verify claims that matter before acting on them.
