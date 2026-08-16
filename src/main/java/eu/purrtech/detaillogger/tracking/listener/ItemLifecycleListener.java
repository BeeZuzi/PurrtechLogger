package eu.purrtech.detaillogger.tracking.listener;

import eu.purrtech.detaillogger.tracking.ItemTrackingService;
import eu.purrtech.detaillogger.tracking.LocationContext;
import eu.purrtech.detaillogger.tracking.StackMath;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.ItemMergeEvent;
import org.bukkit.event.entity.ItemSpawnEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Ground pickup/drop/spawn, stack-aware (a stack always moves as one unit). Container-sourced
 * pickups (chest/barrel/shulker) and precise slot tracking land with container support; this
 * phase only tracks "picked up by a player" without a specific slot. Since every tracked item's
 * PDC is unique, vanilla can never auto-stack two of them together on its own - {@link #onPickup}
 * builds that merge by hand (see {@link #mergeIntoExistingStacks}), mirroring
 * {@code ContainerListener#tryMerge}'s manual-combine approach for the in-inventory case.
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

        List<UUID> remaining = mergeIntoExistingStacks(player, item, units);
        if (remaining.isEmpty()) {
            // Handled entirely by hand: vanilla can't do this part itself (see the method doc),
            // so we take over placement and removal instead of letting the event resolve normally.
            event.setCancelled(true);
            entity.remove();
            return;
        }
        if (remaining.size() < units.size()) {
            // Partially absorbed into one or more existing stacks - shrink the ground item to
            // just what's left and let vanilla place that remainder into a fresh slot as normal.
            String templateKey = tracking.readTemplateKey(item);
            item.setAmount(remaining.size());
            tracking.writeMergedUnits(item, remaining, templateKey);
            entity.setItemStack(item);
        }

        // Slot isn't known at pickup time - Bukkit merges it into the inventory after this event
        // fires. Recorded without a slot; ContainerListener's InventoryOpenEvent/click scans (or
        // the next PlayerJoinEvent) reconcile the exact slot once the player next interacts.
        tracking.recordLocationForAll(remaining, LocationContext.playerInventory(player, -1), "PICKED_UP", player);
    }

    /**
     * Two physically identical tracked items (same template, different UUID) never look
     * "similar" to Bukkit - every unit's PDC is unique by design - so vanilla's own pickup logic
     * always lands a newly-picked-up tracked item in a fresh slot instead of stacking it onto a
     * matching tracked stack already in the inventory, even though they're the same item in every
     * way that matters to the player. This detects that case and builds the merge by hand,
     * spreading across as many existing same-template stacks as it takes (not just one), mirroring
     * {@code ContainerListener#tryMerge}/{@code #consolidate}. Returns whatever units couldn't be
     * absorbed anywhere (empty if the whole pickup was merged away).
     */
    private List<UUID> mergeIntoExistingStacks(Player player, ItemStack picked, List<UUID> pickedUnits) {
        String templateKey = tracking.readTemplateKey(picked);
        if (templateKey == null) {
            return pickedUnits;
        }
        ItemStack[] storage = player.getInventory().getStorageContents();
        List<UUID> remaining = pickedUnits;

        for (int slot = 0; slot < storage.length && !remaining.isEmpty(); slot++) {
            ItemStack existing = storage[slot];
            if (existing == null || existing.getType() != picked.getType()) {
                continue;
            }
            List<UUID> existingUnits = tracking.readAllUnits(existing);
            if (existingUnits.isEmpty() || !templateKey.equals(tracking.readTemplateKey(existing))) {
                continue;
            }

            StackMath.MergeResult sliced = StackMath.mergeUnits(
                    toStrings(existingUnits), toStrings(remaining), existing.getMaxStackSize(), remaining.size());
            if (sliced.destination().size() == existingUnits.size()) {
                continue; // this slot's already full - try the next one
            }

            List<UUID> newExistingUnits = toUuids(sliced.destination());
            ItemStack mergedStack = existing.clone();
            mergedStack.setAmount(newExistingUnits.size());
            tracking.writeMergedUnits(mergedStack, newExistingUnits, templateKey);
            player.getInventory().setItem(slot, mergedStack);
            tracking.recordLocationForAll(newExistingUnits, LocationContext.playerInventory(player, slot), "MERGED", player);

            remaining = toUuids(sliced.source());
        }
        return remaining;
    }

    private static List<String> toStrings(List<UUID> uuids) {
        return uuids.stream().map(UUID::toString).toList();
    }

    private static List<UUID> toUuids(List<String> strings) {
        return strings.stream().map(UUID::fromString).toList();
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
