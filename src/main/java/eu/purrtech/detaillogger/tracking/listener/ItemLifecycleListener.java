package eu.purrtech.detaillogger.tracking.listener;

import eu.purrtech.detaillogger.tracking.ItemTrackingService;
import eu.purrtech.detaillogger.tracking.LocationContext;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.ItemMergeEvent;
import org.bukkit.event.entity.ItemSpawnEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.UUID;

/**
 * Ground pickup/drop/spawn, stack-aware (a stack always moves as one unit). Container-sourced
 * pickups (chest/barrel/shulker) and precise slot tracking land with container support; this
 * phase only tracks "picked up by a player" without a specific slot.
 */
public final class ItemLifecycleListener implements Listener {

    private final ItemTrackingService tracking;

    public ItemLifecycleListener(ItemTrackingService tracking) {
        this.tracking = tracking;
    }

    @EventHandler
    public void onSpawn(ItemSpawnEvent event) {
        Item entity = event.getEntity();
        ItemStack item = entity.getItemStack();
        if (tracking.isTracked(item)) {
            // Already tracked - a more specific event (e.g. onDrop below) owns the location
            // record for this appearance, so we don't double-log it here.
            return;
        }
        List<UUID> units = tracking.ensureTrackedAll(item, "SPAWNED");
        if (units.isEmpty()) {
            return;
        }
        entity.setItemStack(item);
        tracking.recordLocationForAll(units, LocationContext.ground(entity.getLocation()), "SPAWNED", null);
    }

    @EventHandler
    public void onDrop(PlayerDropItemEvent event) {
        Item entity = event.getItemDrop();
        ItemStack item = entity.getItemStack();
        List<UUID> units = tracking.ensureTrackedAll(item, "DROPPED");
        if (units.isEmpty()) {
            return;
        }
        entity.setItemStack(item);
        // NOTE: if ItemSpawnEvent happens to fire before this for the same drop, onSpawn will
        // already have logged a SPAWNED event for the fresh genesis - a harmless duplicate
        // history row (not a correctness issue), since Bukkit doesn't guarantee ordering between
        // different event types. Confirming/eliminating this needs a live-server check.
        tracking.recordLocationForAll(units, LocationContext.ground(entity.getLocation()), "DROPPED", event.getPlayer());
    }

    @EventHandler
    public void onPickup(EntityPickupItemEvent event) {
        if (!(event.getEntity() instanceof Player player)) {
            return; // only player pickups tracked for now - mob pickups aren't in scope yet
        }
        Item entity = event.getItem();
        ItemStack item = entity.getItemStack();
        List<UUID> units = tracking.ensureTrackedAll(item, "PICKED_UP");
        if (units.isEmpty()) {
            return;
        }
        entity.setItemStack(item);
        // Slot isn't known at pickup time - Bukkit merges it into the inventory after this event
        // fires. Recorded without a slot; ContainerListener's InventoryOpenEvent/click scans (or
        // the next PlayerJoinEvent) reconcile the exact slot once the player next interacts.
        tracking.recordLocationForAll(units, LocationContext.playerInventory(player, -1), "PICKED_UP", player);
    }

    @EventHandler
    public void onMerge(ItemMergeEvent event) {
        // Each tracked unit carries its own distinct PDC (scalar or list), so two tracked ground
        // stacks only ever reach this event with identical content in the first place (Bukkit's
        // own similarity check blocks a merge attempt between genuinely different tracked stacks
        // long before this fires) - if that ever does happen it's already a duplication, and
        // letting the merge proceed would only make it harder to spot. Cancel defensively.
        if (tracking.isTracked(event.getEntity().getItemStack())
                || tracking.isTracked(event.getTarget().getItemStack())) {
            event.setCancelled(true);
        }
    }
}
