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

# Allocation is only reachable by a player joining, which CI has no client for, so the
# plugin's own `sgen simulate` drives it instead. Each sample generates chunks, so the
# count trades coverage against CI minutes.
SIMULATE_SAMPLES="${SIMULATE_SAMPLES:-25}"
SIMULATE_TIMEOUT="${SIMULATE_TIMEOUT:-240}"
LEVEL_SEED="${LEVEL_SEED:-spiralgenesis}"

# Spiral indices consumed per allocation. 1.0 is perfect. Well above that means the safety
# rules are discarding whole cells and pushing players outward, which is the regression
# this guards: a max-roughness or max-pit-depth too strict for real terrain.
MAX_INDEX_RATIO="${MAX_INDEX_RATIO:-3.0}"
# Allocations permitted to exhaust max-scan-attempts and take the fallback. Zero on
# ordinary terrain, where a fallback means the thresholds are unusable. A hostile
# world is deliberately run with a small allowance, because there the fallback is the
# feature under test rather than a defect.
MAX_FALLBACKS="${MAX_FALLBACKS:-0}"

# Must track the safety.min-surface-y default in src/main/resources/config.yml; the
# simulation runs against a stock config.
MIN_SURFACE_Y="${MIN_SURFACE_Y:-63}"

if [[ ! -f "$PLUGIN_JAR" ]]; then
    echo "::error::Plugin jar not found: $PLUGIN_JAR"
    exit 1
fi

# Resolve before the cd below, or a relative path stops pointing at the jar.
PLUGIN_JAR="$(realpath "$PLUGIN_JAR")"

mkdir -p "$WORKDIR/plugins"
cd "$WORKDIR"

# ---------------------------------------------------------------------------
# Resolve and verify the server jar
# ---------------------------------------------------------------------------
API="https://fill.papermc.io/v3/projects/$PLATFORM/versions/$MC_VERSION/builds/latest"
echo "Resolving $PLATFORM $MC_VERSION from $API"

BUILD_JSON="$(curl -fsS --retry 3 --retry-delay 5 -m 60 "$API")"

# Parsed with shell builtins rather than jq so this script also runs on a developer
# machine, where jq is often absent. Everything before "server:default" is dropped, which
# discards the Mojang-mapped download that would otherwise match these patterns first.
SEGMENT="${BUILD_JSON#*\"server:default\":}"
JAR_URL="$(printf '%s' "$SEGMENT" | grep -oE 'https://[^"]+' | head -1)"
JAR_SHA="$(printf '%s' "$SEGMENT" | grep -oE '"sha256":"[a-f0-9]{64}"' | head -1 \
    | grep -oE '[a-f0-9]{64}')"
BUILD_ID="$(printf '%s' "$BUILD_JSON" | grep -oE '"id":[0-9]+' | head -1 | grep -oE '[0-9]+')"
CHANNEL="$(printf '%s' "$BUILD_JSON" | grep -oE '"channel":"[A-Z]+"' | head -1 \
    | sed 's/.*:"//; s/"//')"

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

# A normal world, not a flat one: `sgen simulate` below measures the terrain rules, and a
# flat world sits entirely below min-surface-y, so every allocation would take the fallback
# path and the run would assert nothing. The seed is fixed so a threshold regression shows
# up as a changed number rather than as new terrain.
cat > server.properties <<PROPS
online-mode=false
level-type=minecraft\\:normal
level-seed=$LEVEL_SEED
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

# NOTE: this script is Linux-only in practice. Reading stdin from a FIFO crashes the JVM
# on Windows - jansi's native DLL faults with an access violation during library loading,
# before the server starts. Use scripts/dev-server.sh for local testing on Windows.
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
    # ---------------------------------------------------------------------------
    # Exercise allocation against the generated world
    # ---------------------------------------------------------------------------
    echo "Running $SIMULATE_SAMPLES simulated allocations..."
    echo "sgen simulate $SIMULATE_SAMPLES" >&3 || true

    for ((i = 0; i < SIMULATE_TIMEOUT; i++)); do
        if grep -q 'SIMULATE rejections' server.log 2>/dev/null; then
            echo "Simulation completed after ${i}s."
            break
        fi
        if ! kill -0 "$SERVER_PID" 2>/dev/null; then
            echo "::error::Server exited during simulation."
            break
        fi
        sleep 1
    done

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

# ---------------------------------------------------------------------------
# Allocation assertions
# ---------------------------------------------------------------------------
SUMMARY="$(grep -o 'SIMULATE samples=.*' server.log | tail -1 || true)"

if [[ -z "$SUMMARY" ]]; then
    fail "Simulation never reported a summary within ${SIMULATE_TIMEOUT}s."
else
    echo "----- allocation summary -----"
    echo "$SUMMARY"
    grep -o 'SIMULATE rejections.*' server.log | tail -1 || true
    echo "------------------------------"

    field() { sed -n "s/.*[[:space:]]$1=\([^[:space:]]*\).*/\1/p" <<<"$SUMMARY"; }

    COMPLETED="$(field completed)"
    RATIO="$(field ratio)"
    FALLBACKS="$(field fallbacks)"
    MIN_Y="$(field minY)"

    # Every requested allocation must resolve; a hang or a swallowed exception shows up
    # here as a short count rather than as a silent pass.
    if [[ "$COMPLETED" != "$SIMULATE_SAMPLES" ]]; then
        fail "Only $COMPLETED of $SIMULATE_SAMPLES allocations completed."
    fi

    # The fallback path bypasses the safety rules by design, so it firing at all on normal
    # terrain means the thresholds are unusable. MAX_FALLBACKS raises that bar only for the
    # worlds chosen to be hard.
    if (( FALLBACKS > MAX_FALLBACKS )); then
        fail "$FALLBACKS allocation(s) exhausted max-scan-attempts and used the fallback, allowance is $MAX_FALLBACKS."
    elif (( FALLBACKS > 0 )); then
        echo "note: $FALLBACKS fallback allocation(s), within the allowance of $MAX_FALLBACKS."
    fi

    # Nothing may be placed below the configured floor. The simulation runs against a stock
    # config, so this must track the safety.min-surface-y default in config.yml - if that
    # default changes without this following it, the check silently starts asserting a
    # threshold the plugin no longer uses.
    if [[ -n "$MIN_Y" ]] && (( MIN_Y < MIN_SURFACE_Y )); then
        fail "An allocation landed at Y=$MIN_Y, below the configured min-surface-y of $MIN_SURFACE_Y."
    fi

    if awk -v r="$RATIO" -v m="$MAX_INDEX_RATIO" 'BEGIN { exit !(r > m) }'; then
        fail "Index burn ratio $RATIO exceeds $MAX_INDEX_RATIO - the safety thresholds are rejecting too much terrain."
    fi
fi

if [[ "$FAILED" -ne 0 ]]; then
    echo "Smoke test FAILED for $PLATFORM $MC_VERSION."
    exit 1
fi

echo "Smoke test PASSED for $PLATFORM $MC_VERSION (build $BUILD_ID)."
