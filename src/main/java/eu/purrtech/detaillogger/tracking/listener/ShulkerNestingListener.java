package eu.purrtech.detaillogger.tracking.listener;

import eu.purrtech.detaillogger.tracking.ItemTrackingService;
import eu.purrtech.detaillogger.tracking.LocationContext;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import java.util.List;
import java.util.UUID;

/**
 * Tracks items nested inside a shulker box that's being viewed as a held item (opened via
 * right-click without placing it) - a genuinely different case from a placed shulker, which
 * {@link ContainerListener} already handles as an ordinary block container via
 * {@code Inventory#getLocation()}.
 * <p>
 * A held shulker's virtual inventory has no location and no entity holder, so there's no direct
 * way to ask Bukkit "which ItemStack does this inventory belong to" - this assumes the source is
 * whichever hand the player is holding a shulker box in, which covers the only way vanilla lets a
 * player open one.
 */
public final class ShulkerNestingListener implements Listener {

    private final ItemTrackingService tracking;

    public ShulkerNestingListener(ItemTrackingService tracking) {
        this.tracking = tracking;
    }

    @EventHandler
    public void onOpen(InventoryOpenEvent event) {
        if (!(event.getPlayer() instanceof Player player)) {
            return;
        }
        Inventory inventory = event.getInventory();
        if (inventory.getType() != InventoryType.SHULKER_BOX) {
            return;
        }
        if (inventory.getLocation() != null || inventory.getHolder() instanceof Entity) {
            return; // a placed shulker or an entity-backed one - ContainerListener's job
        }

        HeldShulker held = findHeldShulker(player);
        if (held == null) {
            return; // couldn't identify the source item - skip rather than record a wrong parent
        }

        ItemStack[] contents = inventory.getContents();
        boolean anyTracked = false;
        for (int slot = 0; slot < contents.length; slot++) {
            ItemStack item = contents[slot];
            if (item == null || item.getType().isAir()) {
                continue;
            }
            List<UUID> units = tracking.ensureTrackedAll(item, "IMPORTED");
            if (!units.isEmpty()) {
                inventory.setItem(slot, item);
                anyTracked = true;
            }
        }

        if (!anyTracked) {
            return; // nothing worth anchoring - don't create a container-anchor row for nothing
        }

        tracking.ensureContainerAnchor(held.item()).ifPresent(anchorUuid -> {
            held.writeBack(player);
            for (int slot = 0; slot < contents.length; slot++) {
                ItemStack item = inventory.getItem(slot);
                if (item == null || item.getType().isAir()) {
                    continue;
                }
                List<UUID> units = tracking.readAllUnits(item);
                if (units.isEmpty()) {
                    continue;
                }
                tracking.recordLocationForAll(units, LocationContext.nestedShulker(anchorUuid.toString(), slot), "SEEN", player);
            }
        });
    }

    private HeldShulker findHeldShulker(Player player) {
        PlayerInventory inv = player.getInventory();
        ItemStack mainHand = inv.getItemInMainHand();
        if (isShulkerBox(mainHand)) {
            return new HeldShulker(mainHand, Hand.MAIN);
        }
        ItemStack offHand = inv.getItemInOffHand();
        if (isShulkerBox(offHand)) {
            return new HeldShulker(offHand, Hand.OFF);
        }
        return null;
    }

    private static boolean isShulkerBox(ItemStack item) {
        return item != null && item.getType().name().endsWith("SHULKER_BOX");
    }

    private enum Hand {MAIN, OFF}

    private record HeldShulker(ItemStack item, Hand hand) {
        void writeBack(Player player) {
            PlayerInventory inv = player.getInventory();
            if (hand == Hand.MAIN) {
                inv.setItemInMainHand(item);
            } else {
                inv.setItemInOffHand(item);
            }
        }
    }
}
