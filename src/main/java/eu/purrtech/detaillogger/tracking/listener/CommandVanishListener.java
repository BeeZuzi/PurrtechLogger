package eu.purrtech.detaillogger.tracking.listener;

import eu.purrtech.detaillogger.tracking.ItemTrackingService;
import eu.purrtech.detaillogger.tracking.LocationContext;
import eu.purrtech.detaillogger.tracking.StackDebug;
import org.bukkit.Bukkit;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * A command can take an item out of a player's inventory with no click and no event - an auction
 * listing, a mail, a trade, {@code /clear}. When a player runs a command while carrying tracked units,
 * this notes which ones they carry, and two ticks later (after the command has run) checks which are
 * gone. A gone unit is looked for elsewhere first: another online player's inventory or ender chest,
 * the ground around the player, the player's cursor or an open menu. Found - its location is simply
 * updated (MOVED_BY_COMMAND). Not found - it is booked as VANISHED_BY_COMMAND with the command, and if it
 * ever shows up again that is REAPPEARED_AFTER_COMMAND (see {@link ItemTrackingService#markVanished}),
 * which is explicitly not a duplication.
 * <p>
 * Limit: a command that only OPENS a menu (an auction's confirm screen) takes the item later, on a
 * click - that is not seen here.
 */
public final class CommandVanishListener implements Listener {

    /** Commands never worth recording - they carry credentials. */
    private static final Set<String> SENSITIVE = Set.of("login", "l", "register", "reg", "changepassword",
            "changepass", "cp", "unregister", "2fa", "auth");
    private static final int MAX_COMMAND_LENGTH = 120;
    private static final double GROUND_SEARCH_RADIUS = 12;
    private static final long CHECK_DELAY_TICKS = 2;

    private final ItemTrackingService tracking;
    private final Plugin plugin;

    public CommandVanishListener(ItemTrackingService tracking, Plugin plugin) {
        this.tracking = tracking;
        this.plugin = plugin;
    }

    /** The command word without the slash or a {@code namespace:} prefix, lower-cased. */
    static String labelOf(String message) {
        String label = message.startsWith("/") ? message.substring(1) : message;
        label = label.split("\\s+", 2)[0].toLowerCase(Locale.ROOT);
        int colon = label.indexOf(':');
        return colon >= 0 ? label.substring(colon + 1) : label;
    }

    static boolean isSensitive(String message) {
        return SENSITIVE.contains(labelOf(message));
    }

    static String shorten(String message) {
        String command = message.startsWith("/") ? message.substring(1) : message;
        return command.length() > MAX_COMMAND_LENGTH ? command.substring(0, MAX_COMMAND_LENGTH) + "..." : command;
    }

    /** Units in {@code before} that are no longer in {@code after}. */
    static Set<UUID> missing(Set<UUID> before, Set<UUID> after) {
        Set<UUID> gone = new LinkedHashSet<>(before);
        gone.removeAll(after);
        return gone;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        Player player = event.getPlayer();
        if (isSensitive(event.getMessage())) {
            return;
        }
        Set<UUID> before = carriedUnits(player);
        if (before.isEmpty()) {
            return;
        }
        String command = shorten(event.getMessage());
        Bukkit.getScheduler().runTaskLater(plugin, () -> check(player, before, command), CHECK_DELAY_TICKS);
    }

    private void check(Player player, Set<UUID> before, String command) {
        if (!player.isOnline()) {
            return;
        }
        Set<UUID> gone = missing(before, carriedUnits(player));
        if (gone.isEmpty()) {
            return;
        }
        for (UUID unit : gone) {
            if (!traceElsewhere(unit, player, command)) {
                tracking.markVanished(unit, player, command);
                StackDebug.log("VANISHED " + player.getName() + " " + unit + " po prikazu /" + command);
            }
        }
    }

    /** Every tracked unit in the player's inventory (storage, armor, offhand) except reference masters. */
    private Set<UUID> carriedUnits(Player player) {
        Set<UUID> units = new HashSet<>();
        for (ItemStack item : player.getInventory().getContents()) {
            addUnits(units, item);
        }
        return units;
    }

    private void addUnits(Set<UUID> into, ItemStack item) {
        if (item == null || item.getType().isAir()) {
            return;
        }
        for (UUID unit : tracking.readAllUnits(item)) {
            if (!tracking.isReference(unit)) {
                into.add(unit);
            }
        }
    }

    /** Looks for {@code unit} somewhere other than the player's inventory; true (and updates its location) if found. */
    private boolean traceElsewhere(UUID unit, Player owner, String command) {
        // The player's own cursor, and a menu the command opened.
        Set<UUID> here = new HashSet<>();
        addUnits(here, owner.getItemOnCursor());
        if (here.contains(unit)) {
            return true;
        }
        Inventory top = owner.getOpenInventory().getTopInventory();
        if (top.getType() != org.bukkit.event.inventory.InventoryType.CRAFTING) {
            for (ItemStack item : top.getContents()) {
                addUnits(here, item);
            }
            if (here.contains(unit)) {
                return true; // sits in a menu the command opened - not gone (yet)
            }
        }
        // Another online player (a /pay-like command, a trade, mail delivered at once).
        for (Player other : Bukkit.getOnlinePlayers()) {
            if (other.equals(owner)) {
                continue;
            }
            ItemStack[] contents = other.getInventory().getContents();
            for (int slot = 0; slot < contents.length; slot++) {
                if (contents[slot] != null && tracking.readAllUnits(contents[slot]).contains(unit)) {
                    tracking.recordLocation(unit, LocationContext.playerInventory(other, slot), "MOVED_BY_COMMAND", owner);
                    return true;
                }
            }
            for (ItemStack item : other.getEnderChest().getContents()) {
                if (item != null && tracking.readAllUnits(item).contains(unit)) {
                    tracking.recordLocation(unit, LocationContext.enderChest(other, 0), "MOVED_BY_COMMAND", owner);
                    return true;
                }
            }
        }
        // Dropped on the ground nearby (a command that drops items).
        for (Item dropped : owner.getWorld().getNearbyEntitiesByType(Item.class, owner.getLocation(), GROUND_SEARCH_RADIUS)) {
            if (tracking.readAllUnits(dropped.getItemStack()).contains(unit)) {
                tracking.recordLocation(unit, LocationContext.ground(dropped.getLocation()), "MOVED_BY_COMMAND", owner);
                return true;
            }
        }
        return false;
    }

    /** For tests / diagnostics: how many units a player is carrying. */
    int carriedCount(Player player) {
        return carriedUnits(player).size();
    }
}
