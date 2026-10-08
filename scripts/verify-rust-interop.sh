#!/usr/bin/env bash
set -euo pipefail

client_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
server_dir="$(cd -- "$client_dir/../NexusChat-server-rs" && pwd)"
(
    cd "$server_dir"
    CARGO_TARGET_DIR="$server_dir/target" cargo build --offline --example http_contract_fixture
)
cd "$client_dir"
NEXUS_RUST_FIXTURE_BIN="$server_dir/target/debug/examples/http_contract_fixture" \
    bash ./gradlew --offline --no-daemon :shared:jvmTest
