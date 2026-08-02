#!/usr/bin/env bash
set -euo pipefail

usage() {
    printf 'Usage: %s {debug|release|test|lint}\n' "$0" >&2
    exit 64
}

case "${1:-}" in
    debug) task="assembleDebug" ;;
    release) task="assembleRelease" ;;
    test) task="testDebugUnitTest" ;;
    lint) task="lintDebug" ;;
    *) usage ;;
esac

project_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$project_root"

if ! command -v gradle >/dev/null 2>&1; then
    printf 'Gradle was not found. Run this command through mise or install Gradle 9.6.1.\n' >&2
    exit 127
fi

# Direct access is the default for this environment. Set JM_USE_GRADLE_PROXY=1
# when your shell or Gradle user properties provide a working proxy.
gradle_args=()
if [[ "${JM_USE_GRADLE_PROXY:-0}" != "1" ]]; then
    gradle_args+=(
        "-Dhttp.proxyHost="
        "-Dhttps.proxyHost="
    )
fi

# Prefer an ARM64 SDK AAPT2 over the x86 binary that would otherwise run via Box64.
aapt2_path="${ANDROID_AAPT2_PATH:-}"
if [[ -z "$aapt2_path" ]]; then
    sdk_root="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
    if [[ -n "$sdk_root" && -d "$sdk_root/build-tools" ]]; then
        for candidate in "$sdk_root"/build-tools/*/aapt2; do
            if [[ -x "$candidate" ]] && file -b "$candidate" | grep -q 'ARM aarch64'; then
                aapt2_path="$candidate"
            fi
        done
    fi
fi
if [[ -n "$aapt2_path" && -x "$aapt2_path" ]]; then
    gradle_args+=("-Pandroid.aapt2FromMavenOverride=$aapt2_path")
fi

exec gradle "${gradle_args[@]}" "$task" --console=plain
