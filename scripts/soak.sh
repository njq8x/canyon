#!/usr/bin/env bash
#
# Connection churn test: repeatedly logs several protocol clients in and out of
# a running server and checks that it stays healthy.
#
# Each bot performs a full handshake, login, chunk download, idles for a bit
# (answering keepalives like a real client) and disconnects. A soak run catches
# the things a single smoke session cannot: socket or thread leaks, keepalive
# problems, logins that start timing out under load and errors that only appear
# on disconnect.
#
# Leak detection is based on open file descriptors, not threads: the Netty
# stack lazily creates one event loop thread per connection (up to 2*nproc) and
# keeps them until shutdown, which is by design, while a leaked socket always
# shows up as a leaked descriptor.
#
# Usage: scripts/soak.sh <server-jar> <api-jar> [bots] [cycles] [chunks]
#
# Environment:
#   SOAK_NETTY=1         use the new networking stack (default: legacy)
#   SOAK_PORT            port to bind (default: 25601)
#   SOAK_IDLE_MS         how long each bot stays idle after its chunks (2000)
#   SOAK_KEEPALIVES      keepalives each bot waits for before leaving (0)
#   SOAK_FD_LEEWAY       allowed fd growth after all bots leave (default: 2)
#   SOAK_THREAD_LEEWAY   allowed thread growth for the legacy stack (default: 2)
set -euo pipefail

JAR=$(realpath "${1:?usage: soak.sh <server-jar> <api-jar> [bots] [cycles] [chunks]}")
API_JAR=$(realpath "${2:?usage: soak.sh <server-jar> <api-jar> [bots] [cycles] [chunks]}")
BOTS=${3:-3}
CYCLES=${4:-3}
CHUNKS=${5:-10}
IDLE_MS=${SOAK_IDLE_MS:-2000}
KEEPALIVES=${SOAK_KEEPALIVES:-0}

NETTY=${SOAK_NETTY:-0}
PORT=${SOAK_PORT:-25601}
LEEWAY=${SOAK_THREAD_LEEWAY:-2}
FD_LEEWAY=${SOAK_FD_LEEWAY:-2}

ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
WORK=$(mktemp -d)
OUT=$(realpath -m "${SOAK_OUTPUT:-soak.txt}")
PID=

cleanup() {
    if [ -n "${PID}" ] && kill -0 "${PID}" 2>/dev/null; then
        kill "${PID}" 2>/dev/null || true
        wait "${PID}" 2>/dev/null || true
    fi
    rm -rf "${WORK}"
}
trap cleanup EXIT

report() {
    echo "$*" | tee -a "${OUT}"
}

: > "${OUT}"

fail() {
    echo "SOAK TEST FAILED: $*"
    echo "SOAK result=FAILED: $*" >> "${OUT}"
    if [ -f "${WORK}/server.log" ]; then
        echo "----- server.log (first SEVERE) -----"
        grep -m1 -A25 "SEVERE" "${WORK}/server.log" || true
        echo "----- server.log (tail) -----"
        tail -40 "${WORK}/server.log"
    fi
    echo "----- console.log (tail) -----"
    tail -40 "${WORK}/console.log" 2>/dev/null || true
    echo "----- failed bot logs -----"
    for log in "${WORK}"/bot-*.log; do
        [ -f "${log}" ] || continue
        if ! grep -q "BOT PASSED" "${log}"; then
            echo "--- $(basename "${log}") ---"
            cat "${log}"
        fi
    done
    exit 1
}

# --- build the bot against the server jar -----------------------------------
mkdir -p "${WORK}/botclasses"
javac -nowarn -source 8 -target 8 -cp "${JAR}" -d "${WORK}/botclasses" \
    "${ROOT}/scripts/bot/BotClient.java" || fail "could not compile the protocol bot"

# --- boot a fresh server ----------------------------------------------------
cd "${WORK}"
cp "${JAR}" server.jar
printf 'server-port=%s\nonline-mode=false\nlevel-seed=424242\nspawn-animals=false\nspawn-monsters=false\n' \
    "${PORT}" > soak.properties
if [ "${NETTY}" = "1" ]; then
    printf 'settings:\n  use-new-networking-stack: true\n' > canyon.yml
fi
mkfifo console.in
exec 3<> console.in

java -Xms512M -Xmx1G -jar server.jar -c soak.properties < console.in > console.log 2>&1 &
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

thread_count() {
    if [ -d "/proc/${PID}/task" ]; then
        ls "/proc/${PID}/task" | wc -l
    elif command -v jstack >/dev/null 2>&1; then
        jstack "${PID}" 2>/dev/null | grep -c '^"'
    else
        echo 0
    fi
}

fd_count() {
    if [ -d "/proc/${PID}/fd" ]; then
        ls "/proc/${PID}/fd" | wc -l
    elif command -v lsof >/dev/null 2>&1; then
        lsof -p "${PID}" 2>/dev/null | wc -l
    else
        echo 0
    fi
}

wait_for "Done (" 180
sleep 2
BASELINE_THREADS=$(thread_count)
BASELINE_FDS=$(fd_count)

report "SOAK starting bots=${BOTS} cycles=${CYCLES} chunks=${CHUNKS} idle_ms=${IDLE_MS} keepalives=${KEEPALIVES} netty=${NETTY} port=${PORT}"

# --- run the churn ----------------------------------------------------------
FAILED=0
: > "${WORK}/timings.log"

for ((cycle = 1; cycle <= CYCLES; cycle++)); do
    pids=()
    for ((bot = 1; bot <= BOTS; bot++)); do
        java -cp "${JAR}:${WORK}/botclasses" BotClient 127.0.0.1 "${PORT}" \
            "Soak${cycle}x${bot}" "${CHUNKS}" 60000 "${IDLE_MS}" "${KEEPALIVES}" \
            > "${WORK}/bot-${cycle}-${bot}.log" 2>&1 &
        pids+=($!)
    done

    cycle_failed=0
    for pid in "${pids[@]}"; do
        if ! wait "${pid}"; then
            cycle_failed=$((cycle_failed + 1))
        fi
    done

    grep -h "BOT timing" "${WORK}"/bot-${cycle}-*.log >> "${WORK}/timings.log" 2>/dev/null || true
    FAILED=$((FAILED + cycle_failed))
    report "SOAK cycle ${cycle}: bots=${BOTS} failed=${cycle_failed}"

    if [ "${cycle_failed}" -ne 0 ]; then
        grep -h "BOT FAILED\|BOT kicked" "${WORK}"/bot-${cycle}-*.log || true
    fi
done

if [ "${FAILED}" -ne 0 ]; then
    fail "${FAILED} of $((BOTS * CYCLES)) bot sessions did not complete"
fi

# --- the server must survive, settle and still respond ----------------------
sleep 2
kill -0 "${PID}" 2>/dev/null || fail "the server died during the soak"

# Connections that are torn down must not leave sockets (or legacy reader and
# writer threads) behind. Give the server a moment to notice and settle.
AFTER_THREADS=$(thread_count)
AFTER_FDS=$(fd_count)
for _ in $(seq 1 15); do
    if [ $((AFTER_FDS - BASELINE_FDS)) -le "${FD_LEEWAY}" ] \
        && { [ "${NETTY}" = "1" ] || [ $((AFTER_THREADS - BASELINE_THREADS)) -le "${LEEWAY}" ]; }; then
        break
    fi
    sleep 1
    AFTER_THREADS=$(thread_count)
    AFTER_FDS=$(fd_count)
done

THREAD_DELTA=$((AFTER_THREADS - BASELINE_THREADS))
FD_DELTA=$((AFTER_FDS - BASELINE_FDS))

report "SOAK fds before=${BASELINE_FDS} after=${AFTER_FDS} (delta ${FD_DELTA})"
report "SOAK threads before=${BASELINE_THREADS} after=${AFTER_THREADS} (delta ${THREAD_DELTA})"

echo "save-all" >&3
wait_for "Save complete." 60

# --- assertions -------------------------------------------------------------
LOST=$(grep -c "lost connection" server.log || true)
EXPECTED=$((BOTS * CYCLES))
report "SOAK disconnects logged=${LOST}/${EXPECTED}"

grep -q "Stopping server\|Save complete." server.log || fail "the server stopped responding"

if grep -qE "SEVERE|Exception in thread" server.log; then
    fail "log contains an error"
fi

if grep -q "Read timed out" server.log; then
    fail "a connection timed out"
fi

if [ "${LOST}" -lt "${EXPECTED}" ]; then
    fail "the server only noticed ${LOST} of ${EXPECTED} disconnects"
fi

if [ "${FD_DELTA}" -gt "${FD_LEEWAY}" ]; then
    fail "open file descriptors grew by ${FD_DELTA} (before ${BASELINE_FDS}, after ${AFTER_FDS})"
fi

if [ "${NETTY}" = "1" ]; then
    # The Netty stack keeps one event loop thread per connection, lazily
    # created and capped by the worker group size (2 * cores). Growth up to that
    # cap is expected; anything beyond it is a runaway thread leak.
    CPUS=$(nproc 2>/dev/null || echo 2)
    THREAD_CAP=$((2 * CPUS + LEEWAY))
    if [ "${THREAD_DELTA}" -gt "${THREAD_CAP}" ]; then
        fail "netty thread count grew by ${THREAD_DELTA}, above the ${THREAD_CAP} event loop cap (before ${BASELINE_THREADS}, after ${AFTER_THREADS})"
    fi
elif [ "${THREAD_DELTA}" -gt "${LEEWAY}" ]; then
    fail "thread count grew by ${THREAD_DELTA} (before ${BASELINE_THREADS}, after ${AFTER_THREADS})"
fi

report "SOAK login_ms max: $(sed -n 's/.*login_ms=\([0-9.]*\).*/\1/p' "${WORK}/timings.log" | sort -n | tail -1)"
report "SOAK chunks_ms max: $(sed -n 's/.*chunks_ms=\([0-9.]*\).*/\1/p' "${WORK}/timings.log" | sort -n | tail -1)"

TOTAL_CHUNKS=$(sed -n 's/.*BOT chunks=\([0-9]*\).*/\1/p' "${WORK}"/bot-*.log | awk '{sum += $1} END {print sum + 0}')
TOTAL_UNIQUE=$(sed -n 's/.*unique_chunks=\([0-9]*\).*/\1/p' "${WORK}"/bot-*.log | awk '{sum += $1} END {print sum + 0}')
report "SOAK chunks sent=${TOTAL_CHUNKS} distinct=${TOTAL_UNIQUE}"

echo "stop" >&3
wait "${PID}" || fail "server exited with a non-zero status"
PID=

report "SOAK result=PASSED bots=${BOTS} cycles=${CYCLES} chunks=${CHUNKS} netty=${NETTY}"
echo "SOAK TEST PASSED"
