#!/usr/bin/env bash
#
# Boots a Canyon server with a world generator that throws for one chunk and
# checks that a failed chunk load does not take the chunk-loading stack down
# with it.
#
# A failed postLoadTask used to leave the load future uncompleted: every
# getChunkAtAsync() waiter hung forever and the dead task stayed in loadQueue,
# so a later request for the same chunk got the same never-completing future.
# The plugin requests the broken chunk twice, then a healthy chunk, and prints
# CHUNKFAIL RESULT PASS only if both broken loads failed and the healthy one
# completed. Async chunk loading is required for the load queue to be involved.
#
# Usage: scripts/chunkfail-test.sh <server-jar> <api-jar>
#
# Environment:
#   CHUNKFAIL_PORT  port to bind (default: 25597)
set -euo pipefail

JAR=$(realpath "${1:?usage: chunkfail-test.sh <server-jar> <api-jar>}")
API_JAR=$(realpath "${2:?usage: chunkfail-test.sh <server-jar> <api-jar>}")

PORT=${CHUNKFAIL_PORT:-25597}

ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
WORK=$(mktemp -d)
PID=

cleanup() {
    if [ -n "${PID}" ] && kill -0 "${PID}" 2>/dev/null; then
        kill "${PID}" 2>/dev/null || true
        wait "${PID}" 2>/dev/null || true
    fi
    rm -rf "${WORK}"
}
trap cleanup EXIT

fail() {
    echo "CHUNKFAIL TEST FAILED: $*"
    if [ -f "${WORK}/server.log" ]; then
        echo "----- server.log (first SEVERE) -----"
        grep -m1 -A25 "SEVERE" "${WORK}/server.log" || true
        echo "----- server.log (tail) -----"
        tail -40 "${WORK}/server.log"
    fi
    echo "----- console.log (tail) -----"
    tail -40 "${WORK}/console.log" 2>/dev/null || true
    exit 1
}

# --- build the test plugin against the freshly built API --------------------
mkdir -p "${WORK}/plugins" "${WORK}/src/chunkfailtest" "${WORK}/src" "${WORK}/classes"
cp "${ROOT}/scripts/chunkfail/ChunkFailPlugin.java" "${WORK}/src/chunkfailtest/"
cp "${ROOT}/scripts/chunkfail/plugin.yml" "${WORK}/src/"

# The plugin touches net.minecraft.server classes, so compile it against the
# shaded server jar (which also carries the API), not just the API jar.
javac -nowarn -source 8 -target 8 -cp "${JAR}:${API_JAR}" -d "${WORK}/classes" \
    "${WORK}/src/chunkfailtest/ChunkFailPlugin.java" || fail "could not compile the chunk-failure plugin"

jar cf "${WORK}/plugins/chunkfailtest.jar" -C "${WORK}/classes" . -C "${WORK}/src" plugin.yml \
    || fail "could not package the chunk-failure plugin"

# --- boot the server with the throwing generator ----------------------------
cd "${WORK}"
cp "${JAR}" server.jar
printf 'server-port=%s\nonline-mode=false\nlevel-seed=424242\n' "${PORT}" > chunkfail.properties
printf 'worlds:\n  world:\n    generator: chunkfailtest:throw\n' > bukkit.yml
printf 'settings:\n  async-chunk-loading: true\n' > canyon.yml
mkfifo console.in
exec 3<> console.in

java -Xms512M -Xmx1G -jar server.jar -c chunkfail.properties < console.in > console.log 2>&1 &
PID=$!

wait_for() {
    local pattern=$1
    local timeout=$2
    local i

    for ((i = 0; i < timeout; i++)); do
        if grep -q -- "${pattern}" server.log 2>/dev/null; then
            return 0
        fi
        if ! kill -0 "${PID}" 2>/dev/null; then
            fail "server exited while waiting for '${pattern}'"
        fi
        sleep 1
    done

    fail "timed out waiting for '${pattern}'"
}

wait_for "Done (" 180
wait_for "CHUNKFAIL RESULT" 120

grep -q "CHUNKFAIL RESULT PASS" server.log || fail "a failed chunk load was not reported correctly"
grep -q "deliberate generator failure" server.log \
    || fail "the deliberate generator failure was not logged"

echo "stop" >&3
wait "${PID}" || fail "server exited with a non-zero status"
PID=

grep -q "Stopping server" server.log || fail "server did not shut down cleanly"

echo "CHUNKFAIL TEST PASSED"
