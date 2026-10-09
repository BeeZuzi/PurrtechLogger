package eu.purrtech.detaillogger.tracking.listener;

import eu.purrtech.detaillogger.tracking.LedgerService;
import io.papermc.paper.event.player.PlayerInventorySlotChangeEvent;
import org.bukkit.Bukkit;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.event.server.ServerCommandEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;

/**
 * Feeds {@link LedgerService}: what changed (slot changes), why (the ordinary things that move items -
 * click, pickup, drop, craft, close, interact, give commands) and the actions it audits (clicks, drags,
 * hopper moves). Every handler first rejects what is not a ledger item, because hopper and slot-change
 * events fire all the time.
 */
public final class LedgerListener implements Listener {

    private final LedgerService ledger;
    private final Plugin plugin;

    public LedgerListener(LedgerService ledger, Plugin plugin) {
        this.ledger = ledger;
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onSlotChange(PlayerInventorySlotChangeEvent event) {
        ledger.slotChanged(event.getPlayer(), event.getOldItemStack(), event.getNewItemStack());
    }

    // ---- causes ----

    @EventHandler(priority = EventPriority.MONITOR)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        ledger.mark(player.getUniqueId(), "CLICK");
        List<ItemStack> involved = new ArrayList<>();
        involved.add(event.getCurrentItem());
        involved.add(event.getCursor());
        if (event.getHotbarButton() >= 0) {
            involved.add(player.getInventory().getItem(event.getHotbarButton()));
        }
        ledger.watchTransaction(player, event.getView(), involved);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDrag(InventoryDragEvent event) {
        if (event.getWhoClicked() instanceof Player player) {
            ledger.mark(player.getUniqueId(), "CLICK");
            ledger.watchTransaction(player, event.getView(), List.of(event.getOldCursor()));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onClose(InventoryCloseEvent event) {
        // Closing hands the cursor and the crafting grid back to the inventory with no click.
        ledger.mark(event.getPlayer().getUniqueId(), "CLOSE");
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPickup(EntityPickupItemEvent event) {
        if (event.getEntity() instanceof Player player) {
            ledger.mark(player.getUniqueId(), "PICKUP");
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDrop(PlayerDropItemEvent event) {
        ledger.mark(event.getPlayer().getUniqueId(), "DROP");
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onCraft(CraftItemEvent event) {
        HumanEntity who = event.getWhoClicked();
        ledger.mark(who.getUniqueId(), "CRAFT");
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onSwapHands(PlayerSwapHandItemsEvent event) {
        ledger.mark(event.getPlayer().getUniqueId(), "SWAP");
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInteract(PlayerInteractEvent event) {
        ledger.mark(event.getPlayer().getUniqueId(), "INTERACT"); // item frames, lecterns, buckets...
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        ledger.mark(event.getPlayer().getUniqueId(), "INTERACT");
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerCommand(PlayerCommandPreprocessEvent event) {
        ledger.commandRan(event.getMessage());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onConsoleCommand(ServerCommandEvent event) {
        ledger.commandRan(event.getCommand());
    }

    // ---- hoppers, droppers, hopper minecarts ----

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(InventoryMoveItemEvent event) {
        ledger.watchTransfer(event.getSource(), event.getDestination(), event.getItem());
    }

    // ---- counts ----

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        Bukkit.getScheduler().runTask(plugin, () -> ledger.initCounts(player));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        ledger.forget(event.getPlayer().getUniqueId());
    }
}
