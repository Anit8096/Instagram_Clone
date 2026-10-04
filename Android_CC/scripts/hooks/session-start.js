#!/usr/bin/env node
// SessionStart hook: prints a short Android environment summary. Stdout becomes session context,
// so the agent knows up front whether the android CLI, a device, a JDK and Docker are available.
'use strict';
const { execSync } = require('child_process');
const fs = require('fs');
const os = require('os');
const path = require('path');

function run(cmd) {
  try {
    return execSync(cmd, { stdio: ['ignore', 'pipe', 'ignore'], timeout: 8000 }).toString().trim();
  } catch {
    return null;
  }
}

const isWin = process.platform === 'win32';
const home = os.homedir();
const lines = ['[Android_CC] Environment'];

// Android CLI
const cliCandidates = isWin ? [path.join(home, 'AppData', 'AndroidCLI', 'android.exe')] : [];
const cliOnPath = run(isWin ? 'where android' : 'command -v android');
const cli = cliOnPath ? cliOnPath.split(/\r?\n/)[0] : cliCandidates.find((p) => fs.existsSync(p));
lines.push(`- android CLI: ${cli || 'NOT FOUND (install per the android-cli skill)'}`);

// SDK / adb devices
const sdk = process.env.ANDROID_HOME || process.env.ANDROID_SDK_ROOT ||
  (isWin ? path.join(home, 'AppData', 'Local', 'Android', 'Sdk') : path.join(home, 'Library', 'Android', 'sdk'));
const adb = path.join(sdk, 'platform-tools', isWin ? 'adb.exe' : 'adb');
if (fs.existsSync(adb)) {
  const devices = (run(`"${adb}" devices`) || '').split(/\r?\n/).slice(1).filter((l) => /\tdevice$/.test(l));
  lines.push(`- devices: ${devices.length ? devices.map((d) => d.split('\t')[0]).join(', ') : 'none running'}`);
} else {
  lines.push(`- Android SDK: not found at ${sdk}`);
}

// JDK for Gradle
const jbr = isWin ? 'C:\\Program Files\\Android\\Android Studio\\jbr' : '/Applications/Android Studio.app/Contents/jbr/Contents/Home';
lines.push(`- JAVA_HOME: ${process.env.JAVA_HOME || (fs.existsSync(jbr) ? `unset (use Android Studio JBR: ${jbr})` : 'unset')}`);

// Docker (local backends)
const docker = run('docker info --format "{{.ServerVersion}}"');
lines.push(`- Docker engine: ${docker ? `running (${docker})` : 'not running'}`);

// Project hints
const cwd = process.cwd();
if (fs.existsSync(path.join(cwd, 'docs', 'IMPLEMENTATION_PLAN.md'))) lines.push('- Plan: docs/IMPLEMENTATION_PLAN.md (see Status table)');
if (fs.existsSync(path.join(cwd, 'journeys'))) lines.push('- Journeys: journeys/*.xml (run with /journey)');

process.stdout.write(lines.join('\n') + '\n');
