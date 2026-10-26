package smoke;

import java.util.List;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.event.Event;
import org.bukkit.event.world.WorldListener;
import org.bukkit.event.world.WorldSaveEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Vector;

/**
 * Integration test plugin used by scripts/smoke-test.sh.
 *
 * It exercises the behaviour that unit tests cannot reach: dropped item
 * merging and WorldSaveEvent delivery on an explicit save. Results are
 * printed as "SMOKE ..." lines which the script asserts on.
 */
public class SmokePlugin extends JavaPlugin {

    private static final int DIRT_STACKS = 10;
    private static final int STONE_STACKS = 3;
    private static final int PER_STACK = 10;

    private static final int DIRT_TOTAL = DIRT_STACKS * PER_STACK;
    private static final int STONE_TOTAL = STONE_STACKS * PER_STACK;

    // 100 dirt fits in one full stack plus a remainder, 30 stone in one stack.
    private static final int EXPECTED_DIRT_STACKS = 2;
    private static final int EXPECTED_STONE_STACKS = 1;

    private final WorldListener saveListener = new WorldListener() {
        @Override
        public void onWorldSave(WorldSaveEvent event) {
            log("SMOKE WorldSaveEvent world=" + event.getWorld().getName());
        }
    };

    @Override
    public void onDisable() {
    }

    @Override
    public void onEnable() {
        getServer().getPluginManager().registerEvent(Event.Type.WORLD_SAVE, saveListener, Event.Priority.Normal, this);

        getServer().getScheduler().scheduleSyncDelayedTask(this, new Runnable() {
            @Override
            public void run() {
                World world = getServer().getWorlds().get(0);
                Location base = world.getSpawnLocation().add(0.0D, 10.0D, 0.0D);

                for (int i = 0; i < DIRT_STACKS; ++i) {
                    drop(world, base, Material.DIRT);
                }
                for (int i = 0; i < STONE_STACKS; ++i) {
                    drop(world, base, Material.STONE);
                }

                log("SMOKE spawned");
            }
        }, 40L);

        getServer().getScheduler().scheduleSyncDelayedTask(this, new Runnable() {
            @Override
            public void run() {
                check();
            }
        }, 200L);
    }

    private void drop(World world, Location base, Material material) {
        Item item = world.dropItem(base, new ItemStack(material, PER_STACK));
        item.setVelocity(new Vector(0, 0, 0));
    }

    private void check() {
        World world = getServer().getWorlds().get(0);
        List<Entity> entities = world.getEntities();

        int dirt = 0;
        int dirtStacks = 0;
        int stone = 0;
        int stoneStacks = 0;

        for (Entity entity : entities) {
            if (!(entity instanceof Item)) {
                continue;
            }

            ItemStack stack = ((Item) entity).getItemStack();

            if (stack.getType() == Material.DIRT) {
                dirt += stack.getAmount();
                ++dirtStacks;
            } else if (stack.getType() == Material.STONE) {
                stone += stack.getAmount();
                ++stoneStacks;
            }
        }

        boolean pass = dirt == DIRT_TOTAL
                && dirtStacks == EXPECTED_DIRT_STACKS
                && stone == STONE_TOTAL
                && stoneStacks == EXPECTED_STONE_STACKS;

        log("SMOKE items dirt=" + dirt + "/" + dirtStacks + " stone=" + stone + "/" + stoneStacks);
        log("SMOKE result " + (pass ? "PASS" : "FAIL"));
    }

    private void log(String message) {
        getServer().getLogger().info(message);
    }
}
