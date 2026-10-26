#!/usr/bin/env bash
#
# Boots a Canyon server headlessly, drives it through the console and asserts
# that startup, item merging, WorldSaveEvent delivery and shutdown all work.
#
# This is deliberately an integration test: it runs the real server jar with a
# real plugin, because the interesting failures (patches that stop applying,
# a console that dies without a tty, events that are never fired) do not show
# up at compile time.
#
# Usage: scripts/smoke-test.sh <server-jar> <api-jar>
set -euo pipefail

JAR=$(realpath "${1:?usage: smoke-test.sh <server-jar> <api-jar>}")
API_JAR=$(realpath "${2:?usage: smoke-test.sh <server-jar> <api-jar>}")

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
    echo "SMOKE TEST FAILED: $*"
    echo "----- server.log (tail) -----"
    tail -60 "${WORK}/server.log" 2>/dev/null || true
    exit 1
}

# --- build the test plugin against the freshly built API --------------------
mkdir -p "${WORK}/plugins" "${WORK}/src/smoke" "${WORK}/classes"
cp "${ROOT}/scripts/smoke/SmokePlugin.java" "${WORK}/src/smoke/"
cp "${ROOT}/scripts/smoke/plugin.yml" "${WORK}/src/"

javac -nowarn -source 8 -target 8 -cp "${API_JAR}" -d "${WORK}/classes" \
    "${WORK}/src/smoke/SmokePlugin.java" || fail "could not compile the smoke-test plugin"

jar cf "${WORK}/plugins/smoke.jar" -C "${WORK}/classes" . -C "${WORK}/src" plugin.yml \
    || fail "could not package the smoke-test plugin"

# --- boot the server without a tty, exactly like a service manager would ----
cd "${WORK}"
cp "${JAR}" server.jar
mkfifo console.in
exec 3<> console.in

java -Xms512M -Xmx1G -jar server.jar < console.in > server.log 2>&1 &
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
wait_for "SMOKE result PASS" 120

echo "save-all" >&3
wait_for "SMOKE WorldSaveEvent world=" 60

echo "stop" >&3
wait "${PID}" || fail "server exited with a non-zero status"
PID=

# --- assertions -------------------------------------------------------------
grep -q "Save complete." server.log || fail "save-all did not complete"
grep -q "Stopping server" server.log || fail "server did not shut down cleanly"

if grep -qE "SEVERE|EndOfFileException" server.log; then
    fail "log contains an error or an uncaught console exception"
fi

if grep -q "Unable to create a system terminal" server.log; then
    fail "JLine still probes for a terminal when there is no pty"
fi

echo "SMOKE TEST PASSED"
