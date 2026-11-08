#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
command -v kotlinc >/dev/null || { echo 'kotlinc not found' >&2; exit 1; }
ROOT=$(mktemp -d)
trap 'rm -rf "$ROOT"' EXIT
mapfile -d '' sources < <(find core/domain/src/main core/domain/src/test core/protocol/src/main -name '*.kt' -print0)
kotlinc "${sources[@]}" -include-runtime -d "$ROOT/domain-tests.jar"
java -cp "$ROOT/domain-tests.jar" dev.tailg.core.domain.DomainSmokeTestKt
