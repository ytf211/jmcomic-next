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

exec gradle "${gradle_args[@]}" "$task" --console=plain
