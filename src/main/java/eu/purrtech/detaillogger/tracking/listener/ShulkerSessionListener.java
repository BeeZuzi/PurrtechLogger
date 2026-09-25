package eu.purrtech.detaillogger.tracking.listener;

import eu.purrtech.detaillogger.db.dao.EventDao;
import eu.purrtech.detaillogger.tracking.ItemTrackingService;
import eu.purrtech.detaillogger.tracking.NearbyPlayers;
import eu.purrtech.detaillogger.tracking.ShulkerSessionLog;
import eu.purrtech.detaillogger.tracking.pdc.TrackedItemTag;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Records one {@link ShulkerSessionLog#EVENT_TYPE} event per shulker box open/close: where it was
 * opened, and its contents at open and at close, so the admin GUI can show what the player took
 * out and put in. Covers both placed shulkers and ones opened while held.
 * <p>
 * Snapshots are taken at MONITOR priority (after {@link ContainerListener}/
 * {@link ShulkerNestingListener} have tagged the contents) and with this plugin's tracking PDC
 * stripped, so re-tagging or re-merging a tracked stack during the session isn't mistaken for a
 * different item.
 */
public final class ShulkerSessionListener implements Listener {

    private record OpenSession(Inventory inventory, ItemStack[] before, String shulkerMaterial, String mode,
                               Location location, String unitUuid) {
    }

    private final ItemTrackingService tracking;
    private final TrackedItemTag itemTag;
    private final EventDao eventDao;
    private final Map<UUID, OpenSession> sessions = new ConcurrentHashMap<>();

    public ShulkerSessionListener(ItemTrackingService tracking, TrackedItemTag itemTag, EventDao eventDao) {
        this.tracking = tracking;
        this.itemTag = itemTag;
        this.eventDao = eventDao;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onOpen(InventoryOpenEvent event) {
        if (!(event.getPlayer() instanceof Player player)) {
            return;
        }
        Inventory inventory = event.getInventory();
        if (inventory.getType() != InventoryType.SHULKER_BOX || inventory.getHolder() instanceof Entity) {
            return;
        }

        String material;
        String mode;
        Location location;
        String unitUuid = null;
        if (inventory.getLocation() != null) {
            location = inventory.getLocation().getBlock().getLocation();
            material = location.getBlock().getType().name();
            mode = ShulkerSessionLog.MODE_PLACED;
        } else {
            ItemStack held = heldShulker(player);
            if (held == null) {
                return; // couldn't identify the source item - same rule as ShulkerNestingListener
            }
            location = player.getLocation().getBlock().getLocation();
            material = held.getType().name();
            mode = ShulkerSessionLog.MODE_HELD;
            // Gives the held shulker its own identity so the event (and its history) is attached
            // to that exact shulker. Mutates the held item's meta - written back below.
            unitUuid = tracking.ensureContainerAnchor(held).map(UUID::toString).orElse(null);
            writeBackHeld(player, held);
        }

        sessions.put(player.getUniqueId(),
                new OpenSession(inventory, snapshot(inventory), material, mode, location, unitUuid));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onClose(InventoryCloseEvent event) {
        OpenSession session = sessions.remove(event.getPlayer().getUniqueId());
        if (session == null || session.inventory() != event.getInventory()) {
            return;
        }
        Player player = (Player) event.getPlayer();
        Location loc = session.location();
        String world = loc.getWorld() != null ? loc.getWorld().getName() : null;
        String playerUuid = player.getUniqueId().toString();
        String detail = ShulkerSessionLog.toJson(session.shulkerMaterial(), session.mode(),
                session.before(), snapshot(event.getInventory()));
        eventDao.enqueue(session.unitUuid(), ShulkerSessionLog.EVENT_TYPE, System.currentTimeMillis(), world,
                loc.getBlockX(), loc.getBlockY(), loc.getBlockZ(), playerUuid, detail,
                player.getGameMode().name(),
                NearbyPlayers.capture(world, loc.getBlockX(), loc.getBlockY(), loc.getBlockZ(), playerUuid));
    }

    private ItemStack[] snapshot(Inventory inventory) {
        ItemStack[] contents = inventory.getContents();
        ItemStack[] copy = new ItemStack[ShulkerSessionLog.SLOTS];
        for (int slot = 0; slot < Math.min(contents.length, copy.length); slot++) {
            ItemStack item = contents[slot];
            if (item == null || item.getType().isAir()) {
                continue;
            }
            ItemStack clone = item.clone();
            itemTag.strip(clone);
            copy[slot] = clone;
        }
        return copy;
    }

    private static ItemStack heldShulker(Player player) {
        PlayerInventory inv = player.getInventory();
        if (isShulkerBox(inv.getItemInMainHand())) {
            return inv.getItemInMainHand();
        }
        if (isShulkerBox(inv.getItemInOffHand())) {
            return inv.getItemInOffHand();
        }
        return null;
    }

    private static void writeBackHeld(Player player, ItemStack held) {
        PlayerInventory inv = player.getInventory();
        if (isShulkerBox(inv.getItemInMainHand())) {
            inv.setItemInMainHand(held);
        } else {
            inv.setItemInOffHand(held);
        }
    }

    private static boolean isShulkerBox(ItemStack item) {
        return item != null && item.getType().name().endsWith("SHULKER_BOX");
    }
}
