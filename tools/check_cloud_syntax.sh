#!/usr/bin/env bash
# Offline Kotlin type/syntax check only. NOT an Android build or a JSON behavior test.
set -euo pipefail
cd "$(dirname "$0")/.."
command -v kotlinc >/dev/null || { echo 'kotlinc not found' >&2; exit 1; }
ROOT=$(mktemp -d)
trap 'rm -rf "$ROOT"' EXIT
cat > "$ROOT/json-stub.kt" <<'KT'
package org.json
class JSONObject {
    constructor(value: String)
    constructor(value: Map<String, Any>)
    fun opt(name: String): Any? = null
    fun optString(name: String, fallback: String = ""): String = fallback
    fun optInt(name: String, fallback: Int = 0): Int = fallback
    fun optJSONArray(name: String): JSONArray? = null
    fun optJSONObject(name: String): JSONObject? = null
    fun isNull(name: String): Boolean = false
    companion object { val NULL = Any() }
}
class JSONArray {
    fun length(): Int = 0
    fun optJSONObject(index: Int): JSONObject? = null
}
KT
cat > "$ROOT/session-stub.kt" <<'KT'
package dev.tailg.core.cloud
class SessionVault {
    fun read(): String? = null
    fun generation(): Long = 0L
    fun saveAfterLoginIfCurrent(expectedGeneration: Long, token: String): Boolean = true
    fun clear() = Unit
    fun clearIfCurrent(previousToken: String): Boolean = false
    fun rotateIfCurrent(previousToken: String, newToken: String): Boolean = false
}
KT
mapfile -d '' sources < <(find core/domain/src/main -name '*.kt' -print0)
kotlinc "${sources[@]}" core/cloud/src/main/java/dev/tailg/core/cloud/CloudGateway.kt \
  "$ROOT/json-stub.kt" "$ROOT/session-stub.kt" -nowarn -d "$ROOT/cloud-syntax.jar"
echo 'cloud-syntax: OK (stubs only; no Android runtime or live server test)'
