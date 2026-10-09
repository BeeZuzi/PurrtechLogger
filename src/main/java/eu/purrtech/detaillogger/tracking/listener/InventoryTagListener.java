package eu.purrtech.detaillogger.tracking.listener;

import eu.purrtech.detaillogger.tracking.ItemTrackingService;
import eu.purrtech.detaillogger.tracking.LocationContext;
import io.papermc.paper.event.player.PlayerInventorySlotChangeEvent;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.plugin.Plugin;

import java.util.List;
import java.util.UUID;

/**
 * Gives a tracked-template item its UUID the moment it shows up in a player's inventory, however it
 * got there - a crate plugin's {@code addItem}, {@code /give}, a shop, the creative menu, crafting.
 * Without this an item only got its identity at the next join, container open, ground pickup or
 * stack merge, and a duplication bug in that gap produced copies that each looked legitimate (every
 * untagged copy simply got its own fresh UUID later).
 * <p>
 * {@link PlayerInventorySlotChangeEvent} fires for every change of a slot, so the handler is kept
 * cheap: an item with a tag, or a material no template cares about, is rejected before anything is
 * scheduled. The tagging itself runs one tick later, since the item cannot be changed inside the
 * event; our own {@code setItem} re-fires the event, finds the tag and stops.
 * <p>
 * A tagged newcomer is also merged into an existing stack of the same item, the way a ground pickup
 * is - vanilla cannot stack a tagged item with an untagged one, so without that every give would
 * leave its own one-item stack.
 */
public final class InventoryTagListener implements Listener {

    /** Origin recorded for an item first seen appearing in an inventory (not picked up or dropped). */
    private static final String ORIGIN_ISSUED = "ISSUED";

    private final ItemTrackingService tracking;
    private final Plugin plugin;

    public InventoryTagListener(ItemTrackingService tracking, Plugin plugin) {
        this.tracking = tracking;
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onSlotChange(PlayerInventorySlotChangeEvent event) {
        ItemStack now = event.getNewItemStack();
        if (now.getType().isAir() || !tracking.mayBeTemplate(now.getType()) || tracking.isTracked(now)) {
            return;
        }
        Player player = event.getPlayer();
        int slot = event.getSlot();
        Bukkit.getScheduler().runTask(plugin, () -> tagSlot(player, slot));
    }

    /** Tags every untagged template item in the player's inventory now. Returns how many units were created. */
    public int tagInventory(Player player) {
        int created = 0;
        for (int slot = 0; slot <= 40; slot++) { // 0-35 storage, 36-39 armor, 40 offhand
            created += tagSlot(player, slot);
        }
        return created;
    }

    private int tagSlot(Player player, int slot) {
        if (!player.isOnline()) {
            return 0;
        }
        PlayerInventory inventory = player.getInventory();
        ItemStack item = inventory.getItem(slot);
        if (item == null || item.getType().isAir() || tracking.isTracked(item)) {
            return 0;
        }
        String origin = player.getGameMode() == GameMode.CREATIVE ? "CREATIVE" : ORIGIN_ISSUED;
        List<UUID> units = tracking.ensureTrackedAll(item, origin);
        if (units.isEmpty()) {
            return 0; // not a template item after all
        }
        inventory.setItem(slot, item);

        // Fold it into an existing stack of the same item (storage slots only).
        List<UUID> remaining = slot < 36
                ? ItemLifecycleListener.mergeIntoExistingStacks(tracking, player, item, units, slot) : units;
        if (remaining.isEmpty()) {
            inventory.setItem(slot, null);
        } else {
            if (remaining.size() < units.size()) {
                ItemStack rest = item.clone();
                rest.setAmount(remaining.size());
                tracking.writeMergedUnits(rest, remaining, tracking.readTemplateKey(item));
                inventory.setItem(slot, rest);
            }
            tracking.recordLocationForAll(remaining, LocationContext.playerInventory(player, slot), "SEEN", player);
        }
        return units.size();
    }
}
