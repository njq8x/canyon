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
#
# Environment:
#   SMOKE_NETTY=1  run with settings.use-new-networking-stack enabled and
#                  probe the listener over TCP (default: legacy networking)
#   SMOKE_PORT     port to bind (default: 25599)
set -euo pipefail

JAR=$(realpath "${1:?usage: smoke-test.sh <server-jar> <api-jar>}")
API_JAR=$(realpath "${2:?usage: smoke-test.sh <server-jar> <api-jar>}")

NETTY=${SMOKE_NETTY:-0}
PORT=${SMOKE_PORT:-25599}

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
    echo "----- console.log (tail) -----"
    tail -40 "${WORK}/console.log" 2>/dev/null || true
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

# --- verify the shaded jar ships usable JDBC drivers ------------------------
mkdir -p "${WORK}/driverclasses"
javac -nowarn -source 8 -target 8 -d "${WORK}/driverclasses" \
    "${ROOT}/scripts/smoke/DriverCheck.java" || fail "could not compile the JDBC driver check"

if ! java -cp "${JAR}:${WORK}/driverclasses" DriverCheck > "${WORK}/driver.log" 2>&1; then
    cat "${WORK}/driver.log"
    fail "the bundled JDBC drivers are not usable"
fi
grep -q "DRIVER CHECK PASSED" "${WORK}/driver.log" || fail "the JDBC driver check did not pass"

# --- build the protocol bot against the server jar --------------------------
# It speaks the real Beta 1.7.3 protocol using the server's own packet
# decoder, so it covers login, chunk streaming and disconnect end to end.
mkdir -p "${WORK}/botclasses"
javac -nowarn -source 8 -target 8 -cp "${JAR}" -d "${WORK}/botclasses" \
    "${ROOT}/scripts/bot/BotClient.java" || fail "could not compile the protocol bot"

# --- boot the server without a tty, exactly like a service manager would ----
cd "${WORK}"
cp "${JAR}" server.jar
printf 'server-port=%s\nonline-mode=false\n' "${PORT}" > smoke.properties
if [ "${NETTY}" = "1" ]; then
    printf 'settings:\n  use-new-networking-stack: true\n' > canyon.yml
fi
mkfifo console.in
exec 3<> console.in

java -Xms512M -Xmx1G -jar server.jar -c smoke.properties < console.in > console.log 2>&1 &
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

# -c must select the properties file, not be shadowed by another option
grep -q "Starting Minecraft server on .*:${PORT}" server.log \
    || fail "-c did not select the requested properties file"

if [ "${NETTY}" = "1" ]; then
    wait_for "Using .* channel type" 30
    if ! timeout 5 bash -c "exec 3<>/dev/tcp/127.0.0.1/${PORT}"; then
        fail "the Netty listener did not accept a TCP connection"
    fi
fi

# --- log in over the real protocol, stream chunks, then disconnect ----------
if ! java -cp "${JAR}:${WORK}/botclasses" BotClient 127.0.0.1 "${PORT}" SmokeBot 10 30000 \
        > "${WORK}/bot.log" 2>&1; then
    cat "${WORK}/bot.log"
    fail "the protocol bot did not complete a session"
fi
grep -q "BOT PASSED" "${WORK}/bot.log" || { cat "${WORK}/bot.log"; fail "the protocol bot did not pass"; }

# Give the server a moment to process the disconnect and log it.
for ((i = 0; i < 30; i++)); do
    grep -q "SmokeBot lost connection" server.log && break
    sleep 1
done
grep -q "SmokeBot lost connection" server.log || fail "the server did not notice the bot disconnecting"

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
