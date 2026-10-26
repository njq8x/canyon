# Load benchmark

`scripts/benchmark.sh` boots a real server, runs a fixed sequence of workloads
against a fixed-seed world and prints one `BENCH ...` line per metric. The point
is comparing two builds of Canyon, not producing an absolute score.

## Running

```bash
./canyon p
mvn -B clean package
scripts/benchmark.sh Canyon-Server/target/canyon-server.jar Canyon-API/target/canyon-api.jar
```

Results are written to `benchmark.txt` in the current directory (override with
`BENCH_OUTPUT=/path/to/file`). A run takes about a minute.

The benchmark is also available as the **Benchmark** GitHub Actions workflow
(manual dispatch or weekly), which publishes the results to the job summary and
as an artifact.

## Phases

| Phase | What it does |
|---|---|
| `idle` | 200 ticks with no load |
| `chunkgen` | generates a 16x16 chunk area offset from spawn |
| `churn` | unloads and reloads 64 of those chunks |
| `entities` | drops 2000 items, lets them settle, then measures 200 ticks |

Item merging is disabled and the despawn rate is raised so the item count stays
constant. The world seed is fixed (`424242`) and initial spawn randomisation is
off.

## Reading the output

```
BENCH idle ticks=200 elapsed_ms=9972.81 avg_ms=49.86 p50=50.17 p95=100.85 ...
BENCH chunkgen chunks=256 ms=1264 chunks_per_sec=202.53
BENCH churn unloaded=64 unload_ms=73 reloaded=64 reload_ms=26 ...
BENCH entities ticks=200 elapsed_ms=9966.00 avg_ms=49.83 p50=50.00 p95=100.39 ...
BENCH tps tps1=20.00 tps5=20.00 tps15=20.00
```

- **`avg_ms` is the metric to compare.** Canyon uses the Paper tick loop, which
  sleeps to hit 50 ms and then catches up, so an idle server legitimately
  produces a mix of ~50 ms and ~100 ms intervals. A healthy server averages
  ~50 ms; anything consistently above that means ticks are getting more
  expensive.
- **`chunkgen` and `churn`** are wall-clock timings and should stay stable
  between builds on the same machine.
- **`memory used_mb`** is heap in use at the end of the run; it is noisy, so
  only large changes matter.

Numbers from different machines are not comparable. For a before/after
comparison, run the benchmark twice on the same machine, ideally with nothing
else running.
