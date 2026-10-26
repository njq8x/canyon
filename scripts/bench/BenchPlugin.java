package bench;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Vector;

/**
 * Reproducible load benchmark used by scripts/benchmark.sh.
 *
 * It runs four phases against a fresh, fixed-seed world and prints one
 * "BENCH ..." line per metric so results can be compared between builds:
 *
 *   idle        tick interval distribution with no load
 *   chunkgen    wall time to generate a 16x16 chunk area
 *   churn       wall time to unload and reload 64 chunks
 *   entities    tick interval distribution with 2000 dropped items
 *
 * Tick interval is measured between invocations of a task that runs every
 * tick, so an idle server reports ~50 ms per sample and a loaded one more.
 */
public class BenchPlugin extends JavaPlugin {

    private enum Phase {
        WARMUP, IDLE, CHUNK_GEN, CHURN, ENTITY_SETTLE, ENTITY, DONE
    }

    private static final int WARMUP_TICKS = 100;
    private static final int IDLE_TICKS = 200;
    private static final int SETTLE_TICKS = 100;
    private static final int ENTITY_TICKS = 200;

    // 16x16 chunks, offset from spawn so none of them are loaded yet.
    private static final int GEN_FROM = 8;
    private static final int GEN_TO = 23;

    // 8x8 chunks inside the generated area.
    private static final int CHURN_FROM = 8;
    private static final int CHURN_TO = 15;

    private static final int ITEM_COUNT = 2000;

    private final List<Long> tickNanos = new ArrayList<Long>();

    private Phase phase = Phase.WARMUP;
    private int phaseTicks;
    private long lastTickNanos;
    private long phaseStartNanos;

    private int chunksGenerated;
    private int chunksUnloaded;
    private int chunksReloaded;
    private long unloadMillis;
    private long reloadMillis;
    private int itemsSpawned;

    @Override
    public void onDisable() {
    }

    @Override
    public void onEnable() {
        getServer().getScheduler().scheduleSyncRepeatingTask(this, new Runnable() {
            @Override
            public void run() {
                tick();
            }
        }, 100L, 1L);
    }

    private void tick() {
        long now = System.nanoTime();

        if (lastTickNanos != 0 && phase != Phase.WARMUP) {
            tickNanos.add(now - lastTickNanos);
        }
        lastTickNanos = now;
        ++phaseTicks;

        switch (phase) {
            case WARMUP:
                if (phaseTicks >= WARMUP_TICKS) {
                    enter(Phase.IDLE);
                }
                break;

            case IDLE:
                if (phaseTicks >= IDLE_TICKS) {
                    reportTicks("idle");
                    enter(Phase.CHUNK_GEN);
                }
                break;

            case CHUNK_GEN:
                generateChunks();
                enter(Phase.CHURN);
                break;

            case CHURN:
                churnChunks();
                enter(Phase.ENTITY_SETTLE);
                break;

            case ENTITY_SETTLE:
                if (phaseTicks >= SETTLE_TICKS) {
                    spawnItems();
                    enter(Phase.ENTITY);
                }
                break;

            case ENTITY:
                if (phaseTicks >= ENTITY_TICKS) {
                    reportTicks("entities");
                    finish();
                }
                break;

            case DONE:
                break;

            default:
                break;
        }
    }

    private void enter(Phase next) {
        phase = next;
        phaseTicks = 0;
        phaseStartNanos = System.nanoTime();
        tickNanos.clear();
    }

    private void generateChunks() {
        World world = world();
        long start = System.nanoTime();
        int generated = 0;

        for (int x = GEN_FROM; x <= GEN_TO; ++x) {
            for (int z = GEN_FROM; z <= GEN_TO; ++z) {
                if (world.loadChunk(x, z, true)) {
                    ++generated;
                }
            }
        }

        long millis = (System.nanoTime() - start) / 1000000L;
        chunksGenerated = generated;

        log("BENCH chunkgen chunks=" + generated
                + " ms=" + millis
                + " chunks_per_sec=" + format(generated * 1000.0D / Math.max(1L, millis)));
    }

    private void churnChunks() {
        World world = world();
        int unloaded = 0;
        int reloaded = 0;

        long unloadStart = System.nanoTime();
        for (int x = CHURN_FROM; x <= CHURN_TO; ++x) {
            for (int z = CHURN_FROM; z <= CHURN_TO; ++z) {
                if (world.unloadChunk(x, z, true, false)) {
                    ++unloaded;
                }
            }
        }
        unloadMillis = (System.nanoTime() - unloadStart) / 1000000L;

        long reloadStart = System.nanoTime();
        for (int x = CHURN_FROM; x <= CHURN_TO; ++x) {
            for (int z = CHURN_FROM; z <= CHURN_TO; ++z) {
                if (world.loadChunk(x, z, false)) {
                    ++reloaded;
                }
            }
        }
        reloadMillis = (System.nanoTime() - reloadStart) / 1000000L;

        chunksUnloaded = unloaded;
        chunksReloaded = reloaded;

        log("BENCH churn unloaded=" + unloaded + " unload_ms=" + unloadMillis
                + " reloaded=" + reloaded + " reload_ms=" + reloadMillis
                + " unload_per_sec=" + format(unloaded * 1000.0D / Math.max(1L, unloadMillis))
                + " reload_per_sec=" + format(reloaded * 1000.0D / Math.max(1L, reloadMillis)));
    }

    private void spawnItems() {
        World world = world();
        int spawned = 0;

        for (int i = 0; i < ITEM_COUNT; ++i) {
            int chunkX = GEN_FROM + (i % 16);
            int chunkZ = GEN_FROM + ((i / 16) % 16);
            double x = (chunkX << 4) + 2 + (i % 5) * 2;
            double z = (chunkZ << 4) + 2 + ((i / 5) % 5) * 2;
            int y = world.getHighestBlockYAt((int) x, (int) z) + 3;

            Location location = new Location(world, x + 0.5D, y, z + 0.5D);
            Item item = world.dropItem(location, new ItemStack(Material.DIRT, 1));
            item.setVelocity(new Vector(0, 0, 0));
            ++spawned;
        }

        itemsSpawned = spawned;
        log("BENCH spawned items=" + spawned);
    }

    private void reportTicks(String name) {
        if (tickNanos.isEmpty()) {
            log("BENCH " + name + " ticks=0");
            return;
        }

        List<Long> samples = new ArrayList<Long>(tickNanos);
        Collections.sort(samples);

        long elapsedNanos = System.nanoTime() - phaseStartNanos;
        double elapsedMillis = elapsedNanos / 1000000.0D;

        log("BENCH " + name + " ticks=" + samples.size()
                + " elapsed_ms=" + format(elapsedMillis)
                + " avg_ms=" + format(elapsedMillis / samples.size())
                + " p50=" + millis(percentile(samples, 50))
                + " p95=" + millis(percentile(samples, 95))
                + " p99=" + millis(percentile(samples, 99))
                + " max=" + millis(samples.get(samples.size() - 1)));
    }

    private void finish() {
        phase = Phase.DONE;

        World world = world();
        int items = 0;
        for (Entity entity : world.getEntities()) {
            if (entity instanceof Item) {
                ++items;
            }
        }

        Runtime runtime = Runtime.getRuntime();
        long usedMb = (runtime.totalMemory() - runtime.freeMemory()) / (1024L * 1024L);

        double[] tps = getServer().getTPS();

        log("BENCH entities_present items=" + items + " spawned=" + itemsSpawned);
        log("BENCH chunks_loaded chunks=" + countLoadedChunks(world));
        log("BENCH memory used_mb=" + usedMb);
        log("BENCH tps tps1=" + format(tps[0]) + " tps5=" + format(tps[1]) + " tps15=" + format(tps[2]));
        log("BENCH DONE");
    }

    private int countLoadedChunks(World world) {
        // The API has no chunk count, so walk the generated area.
        int loaded = 0;
        for (int x = GEN_FROM; x <= GEN_TO; ++x) {
            for (int z = GEN_FROM; z <= GEN_TO; ++z) {
                if (world.isChunkLoaded(x, z)) {
                    ++loaded;
                }
            }
        }
        return loaded;
    }

    private World world() {
        return getServer().getWorlds().get(0);
    }

    private static long percentile(List<Long> sorted, int percentile) {
        int index = (int) Math.ceil(percentile / 100.0D * sorted.size()) - 1;
        if (index < 0) {
            index = 0;
        }
        if (index >= sorted.size()) {
            index = sorted.size() - 1;
        }
        return sorted.get(index);
    }

    private static String millis(long nanos) {
        return format(nanos / 1000000.0D);
    }

    private static String format(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }

    private void log(String message) {
        getServer().getLogger().info(message);
    }
}
