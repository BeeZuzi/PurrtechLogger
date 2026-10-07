package eu.purrtech.detaillogger.tracking.listener;

import eu.purrtech.detaillogger.tracking.ItemTrackingService;
import eu.purrtech.detaillogger.tracking.LocationContext;
import eu.purrtech.detaillogger.tracking.StackDebug;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.enchantment.EnchantItemEvent;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Items that are transformed by a station - anvil (rename/repair/combine), smithing table,
 * grindstone, stonecutter, loom, cartography table, crafting table, villager trade, enchanting
 * table. A modified item is a NEW unit ("separate units" per the owner): the result gets fresh
 * UUIDs, the units that went in are retired, and every parent -> child pair is recorded in
 * {@code unit_lineage} so a unit's profile shows what it was made from / turned into.
 * <p>
 * Without this, an anvil result kept its input's UUIDs (the same identity on a renamed item), and
 * crafted-away units stayed "alive" in the database forever.
 * <p>
 * The result slot itself is left entirely to vanilla ({@link ContainerListener} no longer touches
 * it): only vanilla consumes the inputs when a result is taken. Everything here happens a tick
 * AFTER the click, by comparing what the input slots held before with what is left, and looking for
 * the output in the player's inventory/cursor.
 * <p>
 * Furnaces/blast furnaces/smokers and brewing stands are deliberately not handled: they consume
 * their input over time, not at the moment the result is taken, so a click can't tell what was used.
 */
public final class ItemTransformListener implements Listener {

    private static final Set<InventoryType> STATIONS = EnumSet.of(InventoryType.ANVIL, InventoryType.SMITHING,
            InventoryType.GRINDSTONE, InventoryType.STONECUTTER, InventoryType.LOOM, InventoryType.CARTOGRAPHY,
            InventoryType.WORKBENCH, InventoryType.CRAFTING, InventoryType.MERCHANT);

    /** Click actions that actually take the result (a refused click reports NOTHING or a PLACE_*). */
    private static final Set<InventoryAction> TAKES_RESULT = EnumSet.of(InventoryAction.PICKUP_ALL,
            InventoryAction.MOVE_TO_OTHER_INVENTORY, InventoryAction.HOTBAR_SWAP);

    private record InputSlot(int slot, ItemStack before, List<UUID> units) {
    }

    private final ItemTrackingService tracking;
    private final Plugin plugin;

    public ItemTransformListener(ItemTrackingService tracking, Plugin plugin) {
        this.tracking = tracking;
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onResultClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)
                || event.getSlotType() != InventoryType.SlotType.RESULT
                || !TAKES_RESULT.contains(event.getAction())) {
            return;
        }
        Inventory top = event.getView().getTopInventory();
        ItemStack result = event.getCurrentItem();
        if (!STATIONS.contains(top.getType()) || !top.equals(event.getClickedInventory())
                || result == null || result.getType().isAir()) {
            return;
        }

        int resultSlot = event.getSlot();
        List<InputSlot> inputs = new ArrayList<>();
        for (int slot = 0; slot < top.getSize(); slot++) {
            ItemStack item = top.getItem(slot);
            if (slot == resultSlot || item == null || item.getType().isAir()) {
                continue;
            }
            List<UUID> units = tracking.readAllUnits(item);
            if (!units.isEmpty()) {
                inputs.add(new InputSlot(slot, item.clone(), units));
            }
        }
        if (inputs.isEmpty()) {
            return; // nothing tracked went into it
        }
        Set<UUID> outputUnits = new HashSet<>(tracking.readAllUnits(result));
        String resultType = result.getType().name();
        String station = top.getType().name();
        StackDebug.log("TRANSFORM " + player.getName() + " " + station + " vysledek=" + StackDebug.describe(result)
                + " vstupu=" + inputs.size());
        Bukkit.getScheduler().runTask(plugin, () -> settle(player, top, inputs, outputUnits, resultType, station));
    }

    private void settle(Player player, Inventory top, List<InputSlot> inputs, Set<UUID> outputUnits,
                        String resultType, String station) {
        // 1. What did the click use up? Compare each input slot with what it held before.
        List<UUID> consumed = new ArrayList<>();
        for (InputSlot input : inputs) {
            ItemStack after = top.getItem(input.slot());
            int remaining = after == null || after.getType().isAir() ? 0 : after.getAmount();
            int used = Math.max(0, input.before().getAmount() - remaining);
            int n = Math.min(used, input.units().size());
            consumed.addAll(input.units().subList(0, n));
            if (n > 0 && remaining > 0 && after != null) {
                // Some left: it keeps the tail of the unit list (the consumed ones are gone).
                List<UUID> left = input.units().subList(n, input.units().size());
                tracking.writeMergedUnits(after, new ArrayList<>(left.subList(0, Math.min(remaining, left.size()))),
                        tracking.readTemplateKey(after));
                top.setItem(input.slot(), after);
            }
        }
        if (consumed.isEmpty()) {
            StackDebug.log("    transform: nic se nespotrebovalo (klik nic nevzal)");
            return;
        }
        Location where = player.getLocation();
        String relation = "TRANSFORMED_" + station;

        // 2. Where did the output go? It still carries its input's UUIDs, so look for them.
        List<UUID> queue = new ArrayList<>(consumed);
        boolean foundOutput = false;
        ItemStack[] contents = player.getInventory().getContents();
        for (int slot = 0; slot < contents.length; slot++) {
            ItemStack item = contents[slot];
            if (item != null && !item.getType().isAir() && carriesAny(item, outputUnits)) {
                foundOutput |= relabel(player, item, queue, relation, slot);
                player.getInventory().setItem(slot, item);
            }
        }
        ItemStack cursor = player.getItemOnCursor();
        if (!cursor.getType().isAir() && carriesAny(cursor, outputUnits)) {
            foundOutput |= relabel(player, cursor, queue, relation, -1);
            player.setItemOnCursor(cursor);
        }

        // 3. The inputs are gone either way.
        if (foundOutput) {
            tracking.retireUnits(consumed, relation, where, player);
        } else {
            // A plain result (9 diamonds -> a diamond block): record what they became.
            tracking.consumeUnits(consumed, relation, resultType, where, player);
        }
        StackDebug.log("    transform hotovo: spotrebovano " + consumed.size() + " jednotek, vystup "
                + (foundOutput ? "sledovany (nova UUID)" : "nesledovany (" + resultType + ")"));
    }

    private boolean carriesAny(ItemStack item, Set<UUID> units) {
        if (units.isEmpty()) {
            return false;
        }
        for (UUID unit : tracking.readAllUnits(item)) {
            if (units.contains(unit)) {
                return true;
            }
        }
        return false;
    }

    /** Gives one output stack fresh UUIDs, parents taken from what the click consumed. */
    private boolean relabel(Player player, ItemStack stack, List<UUID> parentQueue, String relation, int slot) {
        List<UUID> own = tracking.readAllUnits(stack);
        int take = Math.min(stack.getAmount(), parentQueue.size());
        List<UUID> parents = take > 0 ? new ArrayList<>(parentQueue.subList(0, take)) : own;
        parentQueue.subList(0, take).clear();
        List<UUID> children = tracking.deriveUnits(stack, parents, relation);
        if (children.isEmpty()) {
            return false;
        }
        if (slot >= 0) {
            tracking.recordLocationForAll(children, LocationContext.playerInventory(player, slot), "SEEN", player);
        }
        return true;
    }

    /** Enchanting changes the item in place (same stack, same UUID): that is a new unit too. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEnchant(EnchantItemEvent event) {
        List<UUID> before = tracking.readAllUnits(event.getItem());
        if (before.isEmpty()) {
            return;
        }
        Player player = event.getEnchanter();
        Inventory inventory = event.getInventory();
        Bukkit.getScheduler().runTask(plugin, () -> {
            ItemStack item = inventory.getItem(0);
            if (item == null || !tracking.readAllUnits(item).equals(before)) {
                return; // the slot changed under us - leave it
            }
            List<UUID> children = tracking.deriveUnits(item, before, "TRANSFORMED_ENCHANTING");
            if (children.isEmpty()) {
                return;
            }
            inventory.setItem(0, item);
            tracking.retireUnits(before, "TRANSFORMED_ENCHANTING", player.getLocation(), player);
            StackDebug.log("TRANSFORM " + player.getName() + " ENCHANTING nova UUID, " + before.size() + " puvodnich");
        });
    }
}
