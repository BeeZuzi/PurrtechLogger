package eu.purrtech.detaillogger.tracking;

import eu.purrtech.detaillogger.db.dao.TrackedUnitDao;
import eu.purrtech.detaillogger.tracking.pdc.TrackedItemTag;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.block.BlockState;
import org.bukkit.block.Container;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.BundleMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;

/**
 * The emergency switch: takes this plugin's tags (UUID, UUID list, template key) off items and gives
 * them back exactly as vanilla would have them - so that if tracking misbehaves, or before the plugin is
 * removed for good, nothing is left that stops items stacking. Everything the server can reach is cleaned:
 * online players (inventory, cursor, ender chest), every loaded container, dropped items, item frames and
 * entity inventories, and anything inside shulker boxes and bundles. What it cannot reach - offline players,
 * unloaded chunks - is cleaned when it loads, by {@link eu.purrtech.detaillogger.tracking.listener.TagStripListener}.
 * <p>
 * Loaded chunks are done a few per tick so a large world does not stall the server.
 */
public final class TagStripper {

    private static final int CHUNKS_PER_TICK = 20;

    private final TrackedItemTag itemTag;
    private final TrackedUnitDao trackedUnitDao;
    private final Plugin plugin;

    /** Counters of the run in progress (main thread only). */
    private int stripped;

    public TagStripper(TrackedItemTag itemTag, TrackedUnitDao trackedUnitDao, Plugin plugin) {
        this.itemTag = itemTag;
        this.trackedUnitDao = trackedUnitDao;
        this.plugin = plugin;
    }

    public boolean hasTag(ItemStack item) {
        return item != null && !item.getType().isAir()
                && (!itemTag.readUnits(item).isEmpty() || itemTag.readTemplateKey(item) != null);
    }

    /** Removes the tag from one stack and from whatever it carries inside. True if anything changed. */
    public boolean stripItem(ItemStack item) {
        if (item == null || item.getType().isAir()) {
            return false;
        }
        boolean changed = false;
        if (hasTag(item)) {
            itemTag.strip(item);
            stripped++;
            changed = true;
        }
        ItemMeta meta = item.getItemMeta();
        if (meta instanceof BlockStateMeta stateMeta && stateMeta.hasBlockState()
                && stateMeta.getBlockState() instanceof Container box) {
            if (stripInventory(box.getInventory())) {
                stateMeta.setBlockState(box);
                item.setItemMeta(stateMeta);
                changed = true;
            }
        } else if (meta instanceof BundleMeta bundle && bundle.hasItems()) {
            List<ItemStack> inner = new ArrayList<>(bundle.getItems());
            boolean any = false;
            for (ItemStack stack : inner) {
                any |= stripItem(stack);
            }
            if (any) {
                bundle.setItems(inner);
                item.setItemMeta(bundle);
                changed = true;
            }
        }
        return changed;
    }

    public boolean stripInventory(Inventory inventory) {
        boolean any = false;
        int size = inventory instanceof PlayerInventory ? 41 : inventory.getSize(); // storage + armor + offhand
        for (int slot = 0; slot < size; slot++) {
            ItemStack item = inventory.getItem(slot);
            if (item != null && !item.getType().isAir() && stripItem(item)) {
                inventory.setItem(slot, item);
                any = true;
            }
        }
        return any;
    }

    public void stripPlayer(Player player) {
        stripInventory(player.getInventory());
        ItemStack cursor = player.getItemOnCursor();
        if (stripItem(cursor)) {
            player.setItemOnCursor(cursor);
        }
        stripInventory(player.getEnderChest());
    }

    /** Containers (chests, barrels, hoppers, furnaces...) of one chunk. */
    public void stripContainers(Chunk chunk) {
        for (BlockState state : chunk.getTileEntities(block -> true, false)) {
            if (state instanceof Container container) {
                stripInventory(container.getInventory()); // a double chest is seen twice - the 2nd time is a no-op
            }
        }
    }

    /** Dropped items, item frames and entities with an inventory (minecart chests, chest boats, mules...). */
    public void stripEntities(Collection<? extends Entity> entities) {
        for (Entity entity : entities) {
            if (!entity.isValid()) {
                continue;
            }
            if (entity instanceof Item dropped) {
                ItemStack stack = dropped.getItemStack();
                if (stripItem(stack)) {
                    dropped.setItemStack(stack);
                }
            } else if (entity instanceof ItemFrame frame) {
                ItemStack stack = frame.getItem();
                if (stripItem(stack)) {
                    frame.setItem(stack, false);
                }
            } else if (entity instanceof InventoryHolder holder && !(entity instanceof Player)) {
                stripInventory(holder.getInventory());
            }
        }
    }

    /**
     * Cleans everything that is loaded right now. Players at once, chunks a few per tick; reports to
     * {@code sender} when done and retires the item units in the database.
     */
    public void stripEverything(CommandSender sender) {
        stripped = 0;
        for (Player player : Bukkit.getOnlinePlayers()) {
            stripPlayer(player);
        }
        List<Chunk> chunks = new ArrayList<>();
        for (World world : Bukkit.getWorlds()) {
            chunks.addAll(List.of(world.getLoadedChunks()));
        }
        sender.sendMessage("Odebiram znacky: hraci hotovi (" + stripped + " ks), " + chunks.size()
                + " nactenych chunku se zpracuje po davkach...");
        Iterator<Chunk> remaining = chunks.iterator();
        Bukkit.getScheduler().runTaskTimer(plugin, task -> {
            for (int i = 0; i < CHUNKS_PER_TICK && remaining.hasNext(); i++) {
                Chunk chunk = remaining.next();
                if (chunk.isLoaded()) {
                    stripContainers(chunk);
                    stripEntities(List.of(chunk.getEntities()));
                }
            }
            if (!remaining.hasNext()) {
                task.cancel();
                trackedUnitDao.enqueueRetireItemUnits("TRACKING_DISABLED");
                String done = "Hotovo: odebrano " + stripped + " znacek. Jednotky v DB jsou vyrazene. "
                        + "Hraci offline a nenactene chunky se vycisti, az se nactou.";
                sender.sendMessage(done);
                plugin.getLogger().info(done);
            }
        }, 1L, 1L);
    }
}
