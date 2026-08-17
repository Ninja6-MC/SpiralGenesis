#!/usr/bin/env bash
#
# Boots a throwaway Paper or Folia server with the built plugin installed, waits for
# startup to complete, then shuts it down and asserts the plugin loaded cleanly.
#
# Usage: smoke-test.sh <paper|folia> <mc-version> <path-to-plugin-jar>
#
# Exits non-zero if the server fails to start, the plugin fails to enable, or the log
# contains a plugin-related exception. The full server log is left at $WORKDIR/server.log
# for the caller to upload as an artifact.

set -euo pipefail

PLATFORM="${1:?usage: smoke-test.sh <paper|folia> <mc-version> <plugin-jar>}"
MC_VERSION="${2:?missing minecraft version}"
PLUGIN_JAR="${3:?missing plugin jar path}"

BOOT_TIMEOUT="${BOOT_TIMEOUT:-300}"
STOP_TIMEOUT="${STOP_TIMEOUT:-90}"
WORKDIR="${WORKDIR:-$PWD/run-$PLATFORM}"

if [[ ! -f "$PLUGIN_JAR" ]]; then
    echo "::error::Plugin jar not found: $PLUGIN_JAR"
    exit 1
fi

mkdir -p "$WORKDIR/plugins"
cd "$WORKDIR"

# ---------------------------------------------------------------------------
# Resolve and verify the server jar
# ---------------------------------------------------------------------------
API="https://fill.papermc.io/v3/projects/$PLATFORM/versions/$MC_VERSION/builds/latest"
echo "Resolving $PLATFORM $MC_VERSION from $API"

BUILD_JSON="$(curl -fsS --retry 3 --retry-delay 5 -m 60 "$API")"
JAR_URL="$(jq -r '.downloads["server:default"].url' <<<"$BUILD_JSON")"
JAR_SHA="$(jq -r '.downloads["server:default"].checksums.sha256' <<<"$BUILD_JSON")"
BUILD_ID="$(jq -r '.id' <<<"$BUILD_JSON")"
CHANNEL="$(jq -r '.channel' <<<"$BUILD_JSON")"

if [[ -z "$JAR_URL" || "$JAR_URL" == "null" ]]; then
    echo "::error::Could not resolve a download URL for $PLATFORM $MC_VERSION"
    exit 1
fi

echo "Using $PLATFORM build $BUILD_ID ($CHANNEL)"
curl -fsSL --retry 3 --retry-delay 5 -o server.jar "$JAR_URL"

# The jar is downloaded and then executed, so verify it against the checksum the API
# published rather than trusting the transfer.
echo "$JAR_SHA  server.jar" | sha256sum -c -

# ---------------------------------------------------------------------------
# Minimal server configuration
# ---------------------------------------------------------------------------
echo "eula=true" > eula.txt

# A flat world keeps chunk generation cheap. Note this puts the surface far below the
# plugin's default min-surface-y, so allocation would exercise its fallback path - fine
# for a boot test, but a join test needs a normal world or a lowered threshold.
cat > server.properties <<'PROPS'
online-mode=false
level-type=minecraft\:flat
view-distance=4
simulation-distance=4
spawn-protection=0
max-players=5
motd=SpiralGenesis CI smoke test
enable-command-block=false
PROPS

cp "$PLUGIN_JAR" plugins/
echo "Installed plugin: $(basename "$PLUGIN_JAR")"

# ---------------------------------------------------------------------------
# Boot, wait for readiness, shut down
# ---------------------------------------------------------------------------
rm -f stdin.pipe
mkfifo stdin.pipe

java -Xms1G -Xmx2G -jar server.jar --nogui < stdin.pipe > server.log 2>&1 &
SERVER_PID=$!

# Holding the write end open keeps the server's stdin from seeing EOF immediately.
exec 3> stdin.pipe

cleanup() {
    exec 3>&- 2>/dev/null || true
    if kill -0 "$SERVER_PID" 2>/dev/null; then
        echo "Force-killing server process $SERVER_PID"
        kill -9 "$SERVER_PID" 2>/dev/null || true
    fi
}
trap cleanup EXIT

echo "Waiting up to ${BOOT_TIMEOUT}s for startup..."
booted=0
for ((i = 0; i < BOOT_TIMEOUT; i++)); do
    if grep -q 'Done (' server.log 2>/dev/null; then
        booted=1
        echo "Server reported startup complete after ${i}s."
        break
    fi
    # Fail fast if the JVM died rather than burning the whole timeout.
    if ! kill -0 "$SERVER_PID" 2>/dev/null; then
        echo "::error::Server process exited before startup completed."
        break
    fi
    sleep 1
done

if [[ "$booted" -eq 1 ]]; then
    echo "Requesting graceful shutdown..."
    echo "stop" >&3 || true
    for ((i = 0; i < STOP_TIMEOUT; i++)); do
        kill -0 "$SERVER_PID" 2>/dev/null || break
        sleep 1
    done
fi

exec 3>&- 2>/dev/null || true
wait "$SERVER_PID" 2>/dev/null || true
trap - EXIT

# ---------------------------------------------------------------------------
# Assertions
# ---------------------------------------------------------------------------
echo "----- last 40 log lines -----"
tail -40 server.log || true
echo "-----------------------------"

fail() { echo "::error::$1"; FAILED=1; }
FAILED=0

[[ "$booted" -eq 1 ]] || fail "Server never reached 'Done (' within ${BOOT_TIMEOUT}s."

grep -qi 'Enabling SpiralGenesis' server.log \
    || fail "Plugin was never enabled (no 'Enabling SpiralGenesis' in log)."

# Folia refuses plugins that do not declare folia-supported, and says so explicitly.
if grep -qi 'not marked as supporting Folia' server.log; then
    fail "Server rejected the plugin as not Folia-compatible."
fi

if grep -qi "Could not load 'plugins/" server.log; then
    fail "Plugin jar failed to load."
fi

if grep -qiE 'Error occurred while enabling|Failed to enable' server.log; then
    fail "Plugin threw during enable."
fi

# Any stack trace naming our package is a defect regardless of where it surfaced.
if grep -q 'com\.ninja6\.spiralgenesis' server.log && grep -qE '^\s+at ' server.log; then
    if grep -B5 'at com\.ninja6\.spiralgenesis' server.log | grep -qE 'Exception|Error'; then
        fail "A stack trace referencing com.ninja6.spiralgenesis appeared in the log."
    fi
fi

# The legacy scheduler throws this on Folia; catching it here is the whole point.
if grep -q 'UnsupportedOperationException' server.log; then
    fail "UnsupportedOperationException in log (likely a legacy scheduler call on Folia)."
fi

if [[ "$FAILED" -ne 0 ]]; then
    echo "Smoke test FAILED for $PLATFORM $MC_VERSION."
    exit 1
fi

echo "Smoke test PASSED for $PLATFORM $MC_VERSION (build $BUILD_ID)."
