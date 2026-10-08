#!/usr/bin/env bash
set -euo pipefail

project_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
server_dir="${NEXUS_RUST_PROJECT:-$project_dir/../NexusChat-server-rs}"
sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}"
adb="${ADB:-$sdk/platform-tools/adb}"
tls="${NEXUS_TEST_TLS:-false}"
case "$tls" in
    true) device_port=8443 ;;
    false) device_port=3000 ;;
    *) printf 'NEXUS_TEST_TLS must be true or false.\n' >&2; exit 1 ;;
esac
for tool in initdb pg_ctl createdb cargo curl openssl; do
    if ! command -v "$tool" >/dev/null 2>&1; then
        printf 'Missing required tool: %s\n' "$tool" >&2
        exit 1
    fi
done
if [[ "$tls" == true ]] && ! command -v python3 >/dev/null 2>&1; then
    printf 'TLS verification requires Python 3.\n' >&2
    exit 1
fi
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
if [[ "$("$adb" -s "$ANDROID_SERIAL" reverse --list)" == *"tcp:$device_port"* ]]; then
    printf 'Device already has a port-%s reverse mapping; remove it explicitly first.\n' "$device_port" >&2
    exit 1
fi
cargo build --offline --manifest-path "$server_dir/Cargo.toml" --bin nexuschat-server
scratch_dir="$(mktemp -d /tmp/nexus-android-network.XXXXXXXX)"
server_pid=""
tls_pid=""
reversed=false
cleanup() {
    if [[ "$reversed" == true ]]; then
        "$adb" -s "$ANDROID_SERIAL" reverse --remove "tcp:$device_port" || true
    fi
    if [[ -n "$tls_pid" ]]; then
        kill "$tls_pid" 2>/dev/null || true
        wait "$tls_pid" 2>/dev/null || true
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
endpoint=http://127.0.0.1:3000
certificate=""
if [[ "$tls" == true ]]; then
    umask 077
    openssl req -x509 -newkey rsa:2048 -nodes -sha256 -days 1 \
        -subj '/CN=NexusChat Ephemeral Test CA' \
        -addext 'basicConstraints=critical,CA:TRUE' -addext 'keyUsage=critical,keyCertSign,cRLSign' \
        -keyout "$scratch_dir/ca.key" -out "$scratch_dir/ca.pem" >"$scratch_dir/certificate.log" 2>&1
    openssl req -new -newkey rsa:2048 -nodes -sha256 \
        -config "$project_dir/scripts/tls-test-certificate.cnf" \
        -keyout "$scratch_dir/server.key" -out "$scratch_dir/server.csr" >>"$scratch_dir/certificate.log" 2>&1
    openssl x509 -req -sha256 -days 1 -in "$scratch_dir/server.csr" \
        -CA "$scratch_dir/ca.pem" -CAkey "$scratch_dir/ca.key" -set_serial 1 \
        -extfile "$project_dir/scripts/tls-test-certificate.cnf" -extensions server \
        -out "$scratch_dir/server.pem" >>"$scratch_dir/certificate.log" 2>&1
    python3 "$project_dir/scripts/local-tls-proxy.py" \
        --certificate "$scratch_dir/server.pem" --key "$scratch_dir/server.key" \
        >"$scratch_dir/tls.log" 2>&1 &
    tls_pid=$!
    endpoint=https://127.0.0.1:8443
    ready=false
    for ((attempt = 0; attempt < 50; attempt++)); do
        if ! kill -0 "$tls_pid" 2>/dev/null; then
            printf 'TLS test proxy exited during startup.\n' >&2
            exit 1
        fi
        if curl --fail --silent --max-time 1 --cacert "$scratch_dir/ca.pem" "$endpoint/health" >/dev/null; then
            ready=true
            break
        fi
        sleep 0.2
    done
    if [[ "$ready" != true ]]; then
        printf 'TLS test proxy did not become ready.\n' >&2
        exit 1
    fi
    certificate="$(openssl base64 -A -in "$scratch_dir/ca.pem")"
fi
"$adb" -s "$ANDROID_SERIAL" reverse "tcp:$device_port" "tcp:$device_port"
reversed=true
NEXUS_TEST_SERVER_URL="$endpoint" NEXUS_TEST_CA_BASE64="$certificate" \
    bash "$project_dir/scripts/verify-android-device.sh"
