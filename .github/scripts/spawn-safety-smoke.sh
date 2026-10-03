#!/usr/bin/env bash
# Live Paper 26.3 spawn safety scenarios on a fresh, isolated world. Never production.
# Usage: spawn-safety-smoke.sh <own|trusted|none> <plugin> <fixture> <26.3-bot>
set -euo pipefail
MODE="${1:?missing own|trusted|none mode}"
case "$MODE" in own|trusted|none) ;; *) echo "Unknown safety mode: $MODE"; exit 2 ;; esac
PLUGIN="$(realpath "${2:?missing plugin jar}")"
FIXTURE="$(realpath "${3:?missing SpawnSafety jar}")"
BOT="$(realpath "${4:?missing 26.3 bot jar}")"
for jar in "$PLUGIN" "$FIXTURE" "$BOT"; do
    [[ -s "$jar" ]] || { echo "::error::Missing required jar $jar"; exit 1; }
done
# Reject reused directories instead of allowing old player data or claims to pass a run.
WORKDIR="${WORKDIR:-$PWD/run-safety-$MODE}"
[[ ! -e "$WORKDIR" ]] || { echo "::error::Safety server directory already exists: $WORKDIR"; exit 1; }
mkdir -p "$WORKDIR/plugins/SpiralGenesis"
cd "$WORKDIR"
cp "$PLUGIN" "$FIXTURE" plugins/
curl -fsS --retry 3 --retry-delay 5 -m 60 \
    https://fill.papermc.io/v3/projects/paper/versions/26.3/builds/latest > server-build.json
read -r BUILD URL SHA < <(python3 - <<'PY'
import json
b=json.load(open('server-build.json'))
d=b['downloads']['server:default']
print(b['id'], d['url'], d['checksums']['sha256'])
PY
)
echo "Paper 26.3 build $BUILD"
curl -fsSL --retry 3 --retry-delay 5 -m 120 -o server.jar "$URL"
echo "$SHA  server.jar" | sha256sum -c -
if [[ "$MODE" != none ]]; then
    # Official GP release 16.18.7 declares 26.3 compatibility. Check the downloaded
    # executable against the release author's published SHA512 before booting it.
    curl -fsSL --retry 3 --retry-delay 5 -m 120 -o plugins/GriefPrevention.jar \
        https://cdn.modrinth.com/data/O4o4mKaq/versions/dGfCZHqk/GriefPrevention.jar
    echo 'c9bc692253ba3860327e5c38767ce3dc66c798264fe650a08b4ae888337ff75bc16e9bd1db7b39a514a275bf2cc2a3f1f8cd95cf080b89ae42a0f684fc2bfc66  plugins/GriefPrevention.jar' | sha512sum -c -
fi
printf 'eula=true\n' > eula.txt
cat > server.properties <<'PROPERTIES'
online-mode=false
white-list=false
enforce-whitelist=false
server-ip=127.0.0.1
view-distance=3
simulation-distance=3
spawn-protection=0
max-players=1
level-seed=spiralgenesis
motd=Isolated spawn safety CI
PROPERTIES
cat > plugins/SpiralGenesis/config.yml <<'CONFIG'
cell-size: 64
placement:
  stride: 16
  max-candidates: 4
allocation:
  trigger: ON_JOIN
protection:
  enabled: false
CONFIG
mkfifo stdin.pipe
java -Xms1G -Xmx2G -jar server.jar --nogui < stdin.pipe > server.log 2>&1 &
SERVER_PID=$!
exec 3> stdin.pipe
BOT_PID=""
cleanup() {
    exec 3>&- || true
    [[ -z "$BOT_PID" ]] || kill "$BOT_PID" 2>/dev/null || true
    kill "$SERVER_PID" 2>/dev/null || true
}
trap cleanup EXIT
wait_log() {
    local pattern="$1" file="${2:-server.log}" timeout="${3:-90}"
    for ((i=0; i<timeout; i++)); do
        if grep -q 'SPAWNSAFETY FAIL' server.log; then
            cat server.log; exit 1
        fi
        if grep -q "$pattern" "$file" 2>/dev/null; then return; fi
        kill -0 "$SERVER_PID" 2>/dev/null || { cat server.log; exit 1; }
        sleep 1
    done
    echo "::error::Timed out waiting for $pattern in $file"
    tail -150 server.log
    [[ ! -f bot.log ]] || cat bot.log
    exit 1
}
step() {
    echo "spawnsafety $*" >&3
    wait_log "SPAWNSAFETY PASS $1 "
}
wait_log 'Done (' server.log 300
# Setup must finish before any player connects or the allocation premise is invalid.
step prepare "$MODE"
java -jar "$BOT" 127.0.0.1 25565 GateProbe 240 idle > bot.log 2>&1 &
BOT_PID=$!
wait_log 'BOT position' bot.log 60
wait_log 'Assigned & teleported GateProbe' server.log 60
# The storage flush is asynchronous (five seconds); inspect the durable record after it.
sleep 8
step allocation
if [[ "$MODE" != none ]]; then
    step repair
    step kill
    wait_log 'Repaired plot .* for GateProbe' server.log 60
    sleep 10
    step repaired
fi
step fallback
# Check two distinct respawns and observation windows. The fixture independently counts
# deaths and uncancelled environmental damage, so a death loop cannot satisfy the check.
for cycle in 1 2; do
    # Markers must be fresh: otherwise the second cycle could reuse the first PASS.
    start=$(wc -l < server.log)
    echo 'spawnsafety kill' >&3
    sleep 20
    echo 'spawnsafety held' >&3
    sleep 2
    tail -n +"$((start+1))" server.log > cycle.log
    grep -q 'SPAWNSAFETY PASS held ' cycle.log || { cat server.log; exit 1; }
    ! grep -q 'SPAWNSAFETY FAIL' server.log || { cat server.log; exit 1; }
done
# A successful control must exercise actual repair; missing repair is not coverage.
if [[ "$MODE" != none ]]; then
    grep -q 'SPAWNSAFETY PASS repaired ' server.log
    grep -q '\[GriefPrevention\].*Enabling GriefPrevention v16.18.7' server.log
fi
grep -q 'No safe point found among the .*sending GateProbe to world spawn' server.log || { echo '::error::No exhausted repair evidence'; cat server.log; exit 1; }
[[ "$(grep -c 'BOT died' bot.log)" -eq "$( [[ "$MODE" == none ]] && echo 2 || echo 3 )" ]]
! grep -qE 'SPAWNSAFETY FAIL|SPAWNSAFETY damage|Error occurred while enabling (SpiralGenesis|SpawnSafety|GriefPrevention)|at com.ninja6.spiralgenesis.*|at com.ninja6.spawnsafety.*' server.log
printf 'stop\n' >&3
for ((i=0; i<90; i++)); do
    kill -0 "$SERVER_PID" 2>/dev/null || break
    sleep 1
done
if kill -0 "$SERVER_PID" 2>/dev/null; then echo '::error::Server failed to stop'; exit 1; fi
wait "$SERVER_PID"
cp plugins/SpiralGenesis/data.yml persisted-data.yml
cat persisted-data.yml
echo "Spawn safety PASSED mode=$MODE Paper26.3 build=$BUILD"
