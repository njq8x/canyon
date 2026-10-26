#!/usr/bin/env bash
#
# Runs a reproducible load benchmark against a built Canyon server jar and
# prints the results as "BENCH ..." lines.
#
# Phases: idle ticks, 16x16 chunk generation, 64-chunk unload/reload churn and
# 2000 dropped items. The world is always generated from the same seed with
# item merging disabled, so numbers from two builds are comparable.
#
# Usage: scripts/benchmark.sh <server-jar> <api-jar>
#
# Results are written to ./benchmark.txt unless BENCH_OUTPUT is set.
set -euo pipefail

JAR=$(realpath "${1:?usage: benchmark.sh <server-jar> <api-jar>}")
API_JAR=$(realpath "${2:?usage: benchmark.sh <server-jar> <api-jar>}")

ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
WORK=$(mktemp -d)
OUT=$(realpath -m "${BENCH_OUTPUT:-benchmark.txt}")
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
    echo "BENCHMARK FAILED: $*"
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

# --- build the benchmark plugin against the freshly built API ---------------
mkdir -p "${WORK}/plugins" "${WORK}/src/bench" "${WORK}/classes"
cp "${ROOT}/scripts/bench/BenchPlugin.java" "${WORK}/src/bench/"
cp "${ROOT}/scripts/bench/plugin.yml" "${WORK}/src/"

javac -nowarn -source 8 -target 8 -cp "${API_JAR}" -d "${WORK}/classes" \
    "${WORK}/src/bench/BenchPlugin.java" || fail "could not compile the benchmark plugin"

jar cf "${WORK}/plugins/bench.jar" -C "${WORK}/classes" . -C "${WORK}/src" plugin.yml \
    || fail "could not package the benchmark plugin"

# --- boot a fresh, fixed-seed world ----------------------------------------
cd "${WORK}"
cp "${JAR}" server.jar

cat > bench.properties <<'EOF'
server-port=25600
online-mode=false
level-seed=424242
level-name=world
EOF

cat > canyon.yml <<'EOF'
settings:
  randomize-initial-spawn: false
optimization:
  item-merging: false
  item-despawn-rate: 1000000
EOF

mkfifo console.in
exec 3<> console.in

java -Xms1G -Xmx1G -jar server.jar -c bench.properties < console.in > console.log 2>&1 &
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

wait_for "BENCH DONE" 900

echo "stop" >&3
wait "${PID}" || fail "server exited with a non-zero status"
PID=

# --- report -----------------------------------------------------------------
grep "BENCH " server.log | sed 's/^.*BENCH /BENCH /' | tee "${OUT}"

grep -q "BENCH DONE" "${OUT}" || fail "the benchmark did not finish"

if grep -qE "SEVERE|Exception in thread" server.log; then
    fail "log contains an error"
fi

echo "BENCHMARK PASSED"
