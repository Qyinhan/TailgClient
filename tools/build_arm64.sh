#!/usr/bin/env bash
# Build a test-only, debug-signed arm64-v8a APK. Never embeds user credentials.
set -euo pipefail
cd "$(dirname "$0")/.."
command -v gradle >/dev/null || { echo 'Gradle 8.13 is required; see README.' >&2; exit 1; }
command -v python3 >/dev/null || { echo 'python3 is required.' >&2; exit 1; }
python3 tools/check_repository.py
gradle --no-daemon :core:domain:smokeTest :app:assembleDebug
python3 tools/verify_apk.py app/build/outputs/apk/debug/app-debug.apk
printf '\nDebug APK: %s\n' "$(pwd)/app/build/outputs/apk/debug/app-debug.apk"
