#!/usr/bin/env node
// PreToolUse(Bash) hook: blocks commands that dig into Gradle caches / dependency JARs.
// Declared dependencies are trusted; inspecting their internals wastes time (rules/gradle-and-dependencies.md).
// Exit code 2 blocks the call and shows stderr to the agent.
'use strict';

let input = '';
process.stdin.on('data', (chunk) => (input += chunk));
process.stdin.on('end', () => {
  let command = '';
  try {
    command = JSON.parse(input)?.tool_input?.command || '';
  } catch {
    process.exit(0); // Not our payload; never block on parse errors.
  }

  const touchesCache = /\.gradle[\\/]+caches|modules-2[\\/]+files-2\.1|transforms-\d|[\\/]build[\\/]+generated[\\/]/i.test(command);
  const inspectsJars = /\b(javap|jar\s+t|unzip\s+-l)\b/i.test(command);
  const allowed = /gradlew?\s+--stop|cleanBuildCache/i.test(command);

  if ((touchesCache || inspectsJars) && !allowed) {
    process.stderr.write(
      'Blocked by Android_CC guard: don\'t inspect Gradle caches, dependency JARs or generated sources. ' +
        'Trust the versions in libs.versions.toml and write the code; investigate an API only after a real ' +
        'compile error, using official docs (`android docs search`) instead of decompiling.\n',
    );
    process.exit(2);
  }
  process.exit(0);
});
