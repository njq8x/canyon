package chunkfailtest;

import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import net.minecraft.server.Chunk;
import net.minecraft.server.WorldServer;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Regression check for ChunkProviderServer.ChunkLoadTask, driven by
 * scripts/chunkfail-test.sh.
 *
 * The world generator throws for one chunk. ChunkProviderServer used to leave
 * the chunk-load future uncompleted when postLoadTask threw: every waiter hung
 * forever and the dead task stayed in loadQueue, so asking for the same chunk
 * again returned the same never-completing future. With the fix the future
 * completes exceptionally, the task is dropped and a later load of a healthy
 * chunk still works.
 *
 * The plugin asks for the broken chunk twice (the second request only reaches
 * the I/O thread if the first task was dropped) and then for a healthy chunk,
 * and prints CHUNKFAIL RESULT PASS/FAIL.
 */
public class ChunkFailPlugin extends JavaPlugin {

    private static final int BAD_X = 200;
    private static final int BAD_Z = 200;
    private static final int GOOD_X = 201;
    private static final int GOOD_Z = 202;

    private final AtomicReference<String> firstResult = new AtomicReference<>("pending");
    private final AtomicReference<String> secondResult = new AtomicReference<>("pending");
    private final AtomicReference<String> goodResult = new AtomicReference<>("pending");

    private static final class ThrowingGenerator extends ChunkGenerator {
        @Override
        public byte[] generate(World world, Random random, int x, int z) {
            if (x == BAD_X && z == BAD_Z) {
                throw new IllegalStateException("chunk load test: deliberate generator failure");
            }

            byte[] blocks = new byte[16 * 16 * 128];
            for (int bx = 0; bx < 16; ++bx) {
                for (int bz = 0; bz < 16; ++bz) {
                    for (int y = 0; y < 64; ++y) {
                        int id = y < 60 ? 1 : (y < 63 ? 3 : 2);
                        blocks[(bx * 16 + bz) * 128 + y] = (byte) id;
                    }
                }
            }
            return blocks;
        }

        @Override
        public Location getFixedSpawnLocation(World world, Random random) {
            return new Location(world, 0.5D, 65.0D, 0.5D);
        }
    }

    @Override
    public ChunkGenerator getDefaultWorldGenerator(String worldName, String id) {
        return new ThrowingGenerator();
    }

    private WorldServer world() {
        return ((org.bukkit.craftbukkit.CraftWorld) getServer().getWorlds().get(0)).getHandle();
    }

    private void loadBad(AtomicReference<String> result) {
        CompletableFuture<Chunk> future = world().chunkProviderServer.getChunkAtAsync(BAD_X, BAD_Z);
        future.whenComplete((chunk, failure) -> result.set(
                failure != null ? "failed:" + failure.getClass().getSimpleName() : "completed"));
    }

    private void loadGood() {
        CompletableFuture<Chunk> future = world().chunkProviderServer.getChunkAtAsync(GOOD_X, GOOD_Z);
        future.whenComplete((chunk, failure) -> goodResult.set(failure == null ? "completed" : "failed"));
    }

    private void check() {
        String first = firstResult.get();
        String second = secondResult.get();
        String good = goodResult.get();
        log("CHUNKFAIL first=" + first + " second=" + second + " good=" + good);

        boolean ok = first.startsWith("failed:")
                && second.startsWith("failed:")
                && "completed".equals(good);
        log("CHUNKFAIL RESULT " + (ok ? "PASS" : "FAIL"));
    }

    private void log(String message) {
        getServer().getLogger().info(message);
    }

    @Override
    public void onDisable() {
    }

    @Override
    public void onEnable() {
        // The load failures are expected: the generator throws on purpose.
        // They must not stop the server, which the later tasks prove.
        getServer().getScheduler().scheduleSyncDelayedTask(this, new Runnable() {
            @Override
            public void run() {
                loadBad(firstResult);
            }
        }, 40L);
        getServer().getScheduler().scheduleSyncDelayedTask(this, new Runnable() {
            @Override
            public void run() {
                loadBad(secondResult);
            }
        }, 70L);
        getServer().getScheduler().scheduleSyncDelayedTask(this, new Runnable() {
            @Override
            public void run() {
                loadGood();
            }
        }, 100L);
        getServer().getScheduler().scheduleSyncDelayedTask(this, new Runnable() {
            @Override
            public void run() {
                check();
            }
        }, 140L);
    }
}
