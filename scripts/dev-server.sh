#!/usr/bin/env bash
#
# Boots a local Paper server with the freshly built plugin installed, for manual testing
# of spawn allocation against real generated terrain.
#
# Usage: scripts/dev-server.sh [mc-version] [seed]
#
# Unlike .github/scripts/smoke-test.sh, which boots a flat world and stops immediately,
# this generates a NORMAL world and stays up: allocation only runs when a player joins,
# and a flat world is entirely below min-surface-y, so a flat boot exercises nothing but
# the fallback path.
#
# Why not `./gradlew runServer`: run-paper 2.x resolves servers through PaperMC's v2 API,
# which now returns 403, and every 3.x release requires Gradle 9 while this project is on
# 8.10.2. This resolves through the v3 "fill" API instead, as the CI smoke test does.

set -euo pipefail

MC_VERSION="${1:-1.20.4}"
# A fixed seed keeps terrain identical between runs, so threshold changes can be compared
# against the same ground rather than a fresh world each time.
SEED="${2:-spiralgenesis}"

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
RUNDIR="$REPO_ROOT/run"
SERVER_JAR="$RUNDIR/paper-$MC_VERSION.jar"

cd "$REPO_ROOT"

# ---------------------------------------------------------------------------
# Build the plugin
# ---------------------------------------------------------------------------
echo "==> Building plugin"
./gradlew shadowJar -q

PLUGIN_JAR="$(find build/libs -maxdepth 1 -name '*.jar' \
    ! -name '*-sources.jar' ! -name '*-javadoc.jar' | head -1)"

if [[ -z "$PLUGIN_JAR" ]]; then
    echo "error: no plugin jar found in build/libs" >&2
    exit 1
fi
echo "    $PLUGIN_JAR"

mkdir -p "$RUNDIR/plugins"

# ---------------------------------------------------------------------------
# Resolve and download the server jar (cached between runs)
# ---------------------------------------------------------------------------
if [[ ! -f "$SERVER_JAR" ]]; then
    echo "==> Resolving Paper $MC_VERSION"
    API="https://fill.papermc.io/v3/projects/paper/versions/$MC_VERSION/builds/latest"
    BUILD_JSON="$(curl -fsS --retry 3 --retry-delay 5 -m 60 "$API")"

    # jq is present in CI but not in a stock Git Bash, so slice the payload by hand.
    # Everything before "server:default" is dropped, which discards the Mojang-mapped
    # download that would otherwise match these patterns first.
    SEGMENT="${BUILD_JSON#*\"server:default\":}"
    JAR_URL="$(printf '%s' "$SEGMENT" | grep -oE 'https://[^"]+' | head -1)"
    JAR_SHA="$(printf '%s' "$SEGMENT" | grep -oE '"sha256":"[a-f0-9]{64}"' | head -1 \
        | grep -oE '[a-f0-9]{64}')"

    if [[ -z "$JAR_URL" || -z "$JAR_SHA" ]]; then
        echo "error: could not resolve a download for Paper $MC_VERSION" >&2
        exit 1
    fi

    echo "    $JAR_URL"
    curl -fsSL --retry 3 --retry-delay 5 -o "$SERVER_JAR.tmp" "$JAR_URL"

    # The jar is downloaded and then executed, so verify it rather than trust the transfer.
    echo "$JAR_SHA  $SERVER_JAR.tmp" | sha256sum -c - >/dev/null
    mv "$SERVER_JAR.tmp" "$SERVER_JAR"
    echo "    verified"
else
    echo "==> Using cached $(basename "$SERVER_JAR")"
fi

# ---------------------------------------------------------------------------
# Server configuration
# ---------------------------------------------------------------------------
echo "eula=true" > "$RUNDIR/eula.txt"

# Written once so hand-edits survive; delete the file to regenerate it.
if [[ ! -f "$RUNDIR/server.properties" ]]; then
    cat > "$RUNDIR/server.properties" <<PROPS
# Offline mode so arbitrary usernames can join for multi-player allocation tests.
# Local development only.
online-mode=false
level-type=minecraft\:normal
level-seed=$SEED
view-distance=6
simulation-distance=6
spawn-protection=0
max-players=10
motd=SpiralGenesis dev server
PROPS
fi

cp -f "$PLUGIN_JAR" "$RUNDIR/plugins/"

# ---------------------------------------------------------------------------
# Run
# ---------------------------------------------------------------------------
cat <<'BANNER'

==> Starting. Once in game, to exercise allocation against real terrain:

      /sgen reassign <yourname>     re-runs the allocator
      /sgen info <yourname>         shows index, grid cell and coordinates

    Repeat and watch where you land. Afterwards:

      grep "Assigned & teleported" run/logs/latest.log
      grep "maximum scan attempts" run/logs/latest.log

    Type `stop` to shut down.

BANNER

cd "$RUNDIR"
exec java -Xms1G -Xmx2G -jar "$SERVER_JAR" --nogui
