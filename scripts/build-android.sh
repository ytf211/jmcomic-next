#!/usr/bin/env bash
set -euo pipefail

usage() {
    printf 'Usage: %s {debug|release|test|lint} [arm64-v8a|armeabi-v7a|x86|x86_64]\n' "$0" >&2
    exit 64
}

build_type="${1:-}"
target_abi="${2:-}"
if (( $# > 2 )); then
    usage
fi

case "$build_type" in
    debug) task="assembleDebug" ;;
    release) task="assembleRelease" ;;
    test) task="testDebugUnitTest" ;;
    lint) task="lintDebug" ;;
    *) usage ;;
esac

if [[ -n "$target_abi" ]]; then
    case "$build_type:$target_abi" in
        debug:arm64-v8a|debug:armeabi-v7a|debug:x86|debug:x86_64|\
        release:arm64-v8a|release:armeabi-v7a|release:x86|release:x86_64) ;;
        debug:*|release:*)
            printf 'Unsupported ABI: %s\n' "$target_abi" >&2
            usage
            ;;
        *)
            printf 'ABI selection is supported only for debug and release builds.\n' >&2
            usage
            ;;
    esac
fi

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

worker_count="${GRADLE_MAX_WORKERS:-8}"
if [[ ! "$worker_count" =~ ^[1-9][0-9]*$ ]]; then
    printf 'GRADLE_MAX_WORKERS must be a positive integer.\n' >&2
    exit 64
fi
gradle_args+=("--max-workers=$worker_count")
if [[ -n "$target_abi" ]]; then
    gradle_args+=("-PjmTargetAbi=$target_abi")
fi
printf 'Using Gradle max workers: %s\n' "$worker_count"
if [[ "$build_type" == "debug" || "$build_type" == "release" ]]; then
    if [[ -n "$target_abi" ]]; then
        printf 'Building Android ABI: %s\n' "$target_abi"
    else
        printf 'Building universal Android APK\n'
    fi
fi

exec gradle "${gradle_args[@]}" "$task" --console=plain
