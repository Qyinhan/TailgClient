#!/usr/bin/env bash
# Kotlin compile and behavioural test for Android SharedPreferences-backed theme settings.
# Stubbed Android Context only: this does NOT test an Android device or Compose rendering.
set -euo pipefail
cd "$(dirname "$0")/.."
command -v kotlinc >/dev/null || { echo 'kotlinc required' >&2; exit 1; }
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
KOTLIN_LIB="$(dirname "$(command -v kotlinc)")/../lib"
CORO="$KOTLIN_LIB/kotlinx-coroutines-core-jvm.jar"
[ -f "$CORO" ] || { echo 'Local coroutines jar missing' >&2; exit 1; }
cat > "$TMP/Android.kt" <<'KT'
package android.content
interface SharedPreferences {
    fun getString(key: String, fallback: String?): String?
    fun getBoolean(key: String, fallback: Boolean): Boolean
    fun edit(): Editor
    interface Editor {
        fun putString(key: String, value: String): Editor
        fun putBoolean(key: String, value: Boolean): Editor
        fun commit(): Boolean
    }
}
open class Context {
    val applicationContext: Context get() = this
    open fun getSharedPreferences(name: String, mode: Int): SharedPreferences = FakePrefs
    companion object { const val MODE_PRIVATE = 0 }
}
object FakePrefs : SharedPreferences {
    private val data = mutableMapOf<String, Any>()
    override fun getString(key: String, fallback: String?) = data[key] as? String ?: fallback
    override fun getBoolean(key: String, fallback: Boolean) = data[key] as? Boolean ?: fallback
    override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
        override fun putString(key: String, value: String) = apply { data[key] = value }
        override fun putBoolean(key: String, value: Boolean) = apply { data[key] = value }
        override fun commit() = true
    }
}
KT
cat > "$TMP/Test.kt" <<'KT'
package dev.tailg.client
import android.content.Context
fun main() {
    val repo = UiPreferences(Context())
    check(repo.state.value.theme == UiThemeMode.SYSTEM)
    check(repo.state.value.dynamicColor)
    repo.setTheme(UiThemeMode.DARK)
    repo.setDynamicColor(false)
    check(repo.state.value.theme == UiThemeMode.DARK)
    check(!repo.state.value.dynamicColor)
    val restarted = UiPreferences(Context())
    check(restarted.state.value == UiPreferencesState(UiThemeMode.DARK, false))
    restarted.setTheme(UiThemeMode.SYSTEM)
    restarted.setDynamicColor(true)
    println("ui-preferences: OK (local Android stubs only)")
}
KT
kotlinc -cp "$CORO" app/src/main/java/dev/tailg/client/UiPreferences.kt "$TMP/Android.kt" "$TMP/Test.kt" -include-runtime -d "$TMP/ui-prefs.jar"
java -cp "$CORO:$TMP/ui-prefs.jar" dev.tailg.client.TestKt
