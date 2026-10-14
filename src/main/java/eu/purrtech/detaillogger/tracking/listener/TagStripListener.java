package eu.purrtech.detaillogger.tracking.listener;

import eu.purrtech.detaillogger.tracking.TagStripper;
import io.papermc.paper.event.player.PlayerInventorySlotChangeEvent;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.ItemSpawnEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.util.List;

/**
 * Registered ONLY while tracking is switched off. Keeps removing tags from whatever still carries one
 * and was out of reach of the one-off cleanup: players who were offline, chunks that were not loaded,
 * and items a crate or another plugin hands out with an old tag (e.g. a reference item's copy).
 */
public final class TagStripListener implements Listener {

    private final TagStripper stripper;
    private final Plugin plugin;

    public TagStripListener(TagStripper stripper, Plugin plugin) {
        this.stripper = stripper;
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        Bukkit.getScheduler().runTask(plugin, () -> stripper.stripPlayer(player));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChunkLoad(ChunkLoadEvent event) {
        var chunk = event.getChunk();
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (chunk.isLoaded()) {
                stripper.stripContainers(chunk);
            }
        });
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onEntitiesLoad(EntitiesLoadEvent event) {
        List<org.bukkit.entity.Entity> entities = List.copyOf(event.getEntities());
        Bukkit.getScheduler().runTask(plugin, () -> stripper.stripEntities(entities));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onOpen(InventoryOpenEvent event) {
        stripper.stripInventory(event.getInventory());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onItemSpawn(ItemSpawnEvent event) {
        ItemStack stack = event.getEntity().getItemStack();
        if (stripper.hasTag(stack) && stripper.stripItem(stack)) {
            event.getEntity().setItemStack(stack);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onSlotChange(PlayerInventorySlotChangeEvent event) {
        ItemStack now = event.getNewItemStack();
        if (!stripper.hasTag(now)) {
            return;
        }
        Player player = event.getPlayer();
        int slot = event.getSlot();
        Bukkit.getScheduler().runTask(plugin, () -> {
            ItemStack item = player.getInventory().getItem(slot);
            if (item != null && stripper.stripItem(item)) {
                player.getInventory().setItem(slot, item);
            }
        });
    }
}
