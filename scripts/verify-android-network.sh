#!/usr/bin/env bash
set -euo pipefail

project_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
server_dir="${NEXUS_RUST_PROJECT:-$project_dir/../NexusChat-server-rs}"
sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}"
adb="${ADB:-$sdk/platform-tools/adb}"
for tool in initdb pg_ctl createdb cargo curl openssl; do
    if ! command -v "$tool" >/dev/null 2>&1; then
        printf 'Missing required tool: %s\n' "$tool" >&2
        exit 1
    fi
done
if [[ -z "${ANDROID_SERIAL:-}" || ! -x "$adb" ]]; then
    printf 'Set ANDROID_SERIAL and configure the Android SDK/ADB.\n' >&2
    exit 1
fi
if [[ "$("$adb" -s "$ANDROID_SERIAL" get-state)" != device ]]; then
    printf 'Selected Android device is not ready.\n' >&2
    exit 1
fi
if curl --silent --max-time 2 http://127.0.0.1:3000/health >/dev/null; then
    printf 'Port 3000 is in use; stop the existing local server first.\n' >&2
    exit 1
fi
if [[ "$("$adb" -s "$ANDROID_SERIAL" reverse --list)" == *"tcp:3000"* ]]; then
    printf 'Device already has a port-3000 reverse mapping; remove it explicitly first.\n' >&2
    exit 1
fi
cargo build --offline --manifest-path "$server_dir/Cargo.toml" --bin nexuschat-server
scratch_dir="$(mktemp -d /tmp/nexus-android-network.XXXXXXXX)"
server_pid=""
reversed=false
cleanup() {
    if [[ "$reversed" == true ]]; then
        "$adb" -s "$ANDROID_SERIAL" reverse --remove tcp:3000 || true
    fi
    if [[ -n "$server_pid" ]]; then
        kill "$server_pid" 2>/dev/null || true
        wait "$server_pid" 2>/dev/null || true
    fi
    if [[ -f "$scratch_dir/data/postmaster.pid" ]]; then
        if ! pg_ctl -D "$scratch_dir/data" -m fast -w stop; then
            printf 'Database shutdown failed; retained %s\n' "$scratch_dir" >&2
            return 1
        fi
    fi
    rm -rf -- "$scratch_dir"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
mkdir "$scratch_dir/socket"
initdb -D "$scratch_dir/data" --username=nexus_test --auth-local=trust --auth-host=reject --encoding=UTF8 --locale=C
pg_ctl -D "$scratch_dir/data" -l "$scratch_dir/postgres.log" \
    -o "-c listen_addresses='' -c unix_socket_directories=$scratch_dir/socket" -w start
createdb --host="$scratch_dir/socket" --username=nexus_test --maintenance-db=postgres nexus_test
DATABASE_URL="postgres://nexus_test@localhost/nexus_test?host=$scratch_dir/socket&sslmode=disable" \
NEXUS_MESSAGE_STORAGE=postgres NEXUS_CALL_STORAGE=memory NEXUS_DATABASE_MIGRATE=true \
NEXUS_AUTH_STORAGE=file NEXUS_AUTH_FILE="$scratch_dir/auth.json" \
NEXUS_AUTH_SECRET="$(openssl rand -hex 32)" \
    "$server_dir/target/debug/nexuschat-server" >"$scratch_dir/server.log" 2>&1 &
server_pid=$!
ready=false
for ((attempt = 0; attempt < 50; attempt++)); do
    if ! kill -0 "$server_pid" 2>/dev/null; then
        printf 'Test backend exited during startup.\n' >&2
        exit 1
    fi
    if curl --fail --silent --max-time 1 http://127.0.0.1:3000/health >/dev/null; then
        ready=true
        break
    fi
    sleep 0.2
done
if [[ "$ready" != true ]]; then
    printf 'Test backend did not become ready.\n' >&2
    exit 1
fi
"$adb" -s "$ANDROID_SERIAL" reverse tcp:3000 tcp:3000
reversed=true
NEXUS_TEST_SERVER_URL=http://127.0.0.1:3000 bash "$project_dir/scripts/verify-android-device.sh"
