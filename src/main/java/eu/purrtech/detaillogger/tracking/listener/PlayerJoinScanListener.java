package eu.purrtech.detaillogger.tracking.listener;

import eu.purrtech.detaillogger.tracking.ItemTrackingService;
import eu.purrtech.detaillogger.tracking.LocationContext;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import java.util.List;
import java.util.UUID;

/**
 * Bootstraps tracking for items a player is already carrying on join - either genesis
 * ({@code origin=IMPORTED}, for items that existed before the plugin started watching them) or a
 * reconciling "SEEN" event for already-tracked items (single-unit or merged stack alike). Doubles
 * as the world-state import path for items, since there's no separate world-scan step in this
 * phase.
 * <p>
 * Slot numbers follow Bukkit's standard raw player-inventory numbering: 0-35 main/hotbar (from
 * {@link PlayerInventory#getStorageContents()}), 36-39 armor boots-to-helmet (matching
 * {@link PlayerInventory#getArmorContents()}'s documented order), 40 offhand.
 */
public final class PlayerJoinScanListener implements Listener {

    private static final int ARMOR_SLOT_BASE = 36;
    private static final int OFFHAND_SLOT = 40;

    private final ItemTrackingService tracking;

    public PlayerJoinScanListener(ItemTrackingService tracking) {
        this.tracking = tracking;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        PlayerInventory inv = player.getInventory();

        ItemStack[] storage = inv.getStorageContents();
        for (int slot = 0; slot < storage.length; slot++) {
            ItemStack item = storage[slot];
            if (item == null || item.getType().isAir()) {
                continue;
            }
            List<UUID> units = tracking.ensureTrackedAll(item, "IMPORTED");
            if (!units.isEmpty()) {
                inv.setItem(slot, item);
                tracking.recordLocationForAll(units, LocationContext.playerInventory(player, slot), "SEEN", player);
            }
        }

        ItemStack[] armor = inv.getArmorContents();
        boolean armorChanged = false;
        for (int i = 0; i < armor.length; i++) {
            ItemStack item = armor[i];
            if (item == null || item.getType().isAir()) {
                continue;
            }
            int slot = ARMOR_SLOT_BASE + i;
            List<UUID> units = tracking.ensureTrackedAll(item, "IMPORTED");
            if (!units.isEmpty()) {
                armorChanged = true;
                tracking.recordLocationForAll(units, LocationContext.playerInventory(player, slot), "SEEN", player);
            }
        }
        if (armorChanged) {
            inv.setArmorContents(armor);
        }

        ItemStack offHand = inv.getItemInOffHand();
        if (!offHand.getType().isAir()) {
            List<UUID> units = tracking.ensureTrackedAll(offHand, "IMPORTED");
            if (!units.isEmpty()) {
                inv.setItemInOffHand(offHand);
                tracking.recordLocationForAll(units, LocationContext.playerInventory(player, OFFHAND_SLOT), "SEEN", player);
            }
        }
    }
}
