package eu.purrtech.detaillogger.tracking.listener;

import eu.purrtech.detaillogger.tracking.ItemTrackingService;
import eu.purrtech.detaillogger.tracking.LocationContext;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.InventoryPickupItemEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Extends the location model to block containers (chest/barrel/furnace/hopper/dispenser/placed
 * shulker/...), entity containers (minecart chest, minecart hopper...), ender chests, and - since
 * Phase 9 - any other real chest-style GUI a third-party plugin creates (identified by its
 * {@code InventoryHolder} class or view title, recorded as {@code PLACED_INTO_MENU}). This is
 * independent of DisplayGUI, whose menus have no real inventory slots at all (see
 * {@code ShulkerNestingListener}'s notes) and so never reach this listener in the first place.
 * Since Phase 7, this also keeps merged stacks correct across the clicks/drags that split or
 * combine them.
 * <p>
 * A tracked item that's merely floating on a player's cursor mid-interaction (picked up but not
 * yet placed anywhere) doesn't get its own location row here - its {@code locations} row stays
 * momentarily stale until it lands somewhere. This is a deliberate simplification: closing the
 * inventory auto-returns a cursor item into the main inventory, which the next click/open/join
 * scan reconciles anyway.
 * <p>
 * Out of scope (documented limitation, not attempted here): dispenser ejection
 * ({@code BlockDispenseEvent}), crafting/smelting consuming a partial stack
 * ({@code CraftItemEvent}/{@code FurnaceSmeltEvent}/{@code FurnaceExtractEvent}), anvil combine,
 * and a hopper pulling a partial amount out of an existing merged stack (hopper reconciliation
 * here only tracks where the moved unit(s) ended up, not whether the source stack was correctly
 * de-duplicated afterward).
 */
public final class ContainerListener implements Listener {

    private final ItemTrackingService tracking;
    private final Plugin plugin;

    public ContainerListener(ItemTrackingService tracking, Plugin plugin) {
        this.tracking = tracking;
        this.plugin = plugin;
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        if (tryMerge(event, player)) {
            return; // handled entirely by hand (event cancelled) - nothing left to reconcile
        }

        Inventory clicked = event.getClickedInventory();
        int slot = event.getSlot();
        if (clicked == null || slot < 0) {
            return;
        }
        String viewTitle = plainTitle(event.getView());
        // Scheduled a tick later: at event-dispatch time the click hasn't been resolved by the
        // server yet, so re-reading the slot next tick is the reliable way to see where the item
        // actually ended up, regardless of the exact click type (pickup/place/swap/shift-click).
        Bukkit.getScheduler().runTask(plugin, () -> reconcileClickResult(clicked, slot, player, viewTitle));
    }

    /**
     * Vanilla refuses to auto-combine two stacks whose PDC differs (every tracked unit's is
     * unique), even when the player's intent - left/right-clicking one tracked stack onto another
     * of the same template - is clearly to merge them; left alone it would just swap them. This
     * detects that specific case and builds the merge by hand before the event resolves. Returns
     * true if it handled (and cancelled) the event.
     */
    private boolean tryMerge(InventoryClickEvent event, Player player) {
        if (event.getClick() != ClickType.LEFT && event.getClick() != ClickType.RIGHT) {
            return false; // only the unambiguous direct-click case is merge-assisted
        }
        Inventory clicked = event.getClickedInventory();
        if (clicked == null || event.getSlot() < 0) {
            return false;
        }
        ItemStack current = event.getCurrentItem();
        ItemStack cursor = event.getCursor();
        if (current == null || current.getType().isAir() || cursor == null || cursor.getType().isAir()) {
            return false;
        }
        if (current.getType() != cursor.getType()) {
            return false;
        }

        List<UUID> currentUnits = tracking.readAllUnits(current);
        List<UUID> cursorUnits = tracking.readAllUnits(cursor);
        if (currentUnits.isEmpty() || cursorUnits.isEmpty()) {
            return false; // nothing tracked on at least one side - vanilla handles it fine alone
        }

        String currentTemplate = tracking.readTemplateKey(current);
        String cursorTemplate = tracking.readTemplateKey(cursor);
        if (currentTemplate == null || !currentTemplate.equals(cursorTemplate)) {
            return false; // different templates shouldn't merge even if the material matches
        }

        int combined = currentUnits.size() + cursorUnits.size();
        if (combined > current.getMaxStackSize()) {
            return false; // wouldn't fit - documented limitation, no partial-merge support
        }

        event.setCancelled(true);

        List<UUID> merged = new ArrayList<>(combined);
        merged.addAll(currentUnits);
        merged.addAll(cursorUnits);

        ItemStack mergedStack = current.clone();
        mergedStack.setAmount(combined);
        tracking.writeMergedUnits(mergedStack, merged, currentTemplate);

        clicked.setItem(event.getSlot(), mergedStack);
        player.setItemOnCursor(null);

        resolveContext(clicked, event.getSlot(), player, plainTitle(event.getView()))
                .ifPresent(ctx -> tracking.recordLocationForAll(merged, ctx, "MERGED", player));
        return true;
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        List<Integer> rawSlots = new ArrayList<>(event.getRawSlots());
        if (rawSlots.isEmpty()) {
            return;
        }
        InventoryView view = event.getView();
        // Same reasoning as onClick: schedule a tick later so the drag is fully resolved before
        // reading it back.
        Bukkit.getScheduler().runTask(plugin, () -> reconcileDragResult(view, rawSlots, player));
    }

    @EventHandler
    public void onOpen(InventoryOpenEvent event) {
        if (!(event.getPlayer() instanceof Player player)) {
            return;
        }
        Inventory inventory = event.getInventory();
        InventoryType type = inventory.getType();
        if (type == InventoryType.PLAYER || type == InventoryType.CRAFTING) {
            return;
        }
        if (type == InventoryType.SHULKER_BOX && inventory.getLocation() == null
                && !(inventory.getHolder() instanceof Entity)) {
            return; // held shulker, not a placed one - ShulkerNestingListener owns this case
        }
        String viewTitle = plainTitle(event.getView());
        ItemStack[] contents = inventory.getContents();
        for (int slot = 0; slot < contents.length; slot++) {
            ItemStack item = contents[slot];
            if (item == null || item.getType().isAir()) {
                continue;
            }
            int finalSlot = slot;
            List<UUID> units = tracking.ensureTrackedAll(item, "IMPORTED");
            if (!units.isEmpty()) {
                inventory.setItem(finalSlot, item);
                resolveContext(inventory, finalSlot, player, viewTitle)
                        .ifPresent(ctx -> tracking.recordLocationForAll(units, ctx, "SEEN", player));
            }
        }
    }

    @EventHandler
    public void onHopperMove(InventoryMoveItemEvent event) {
        trackHopperTransfer(tracking.readAllUnits(event.getItem()), event.getDestination());
    }

    @EventHandler
    public void onHopperPickup(InventoryPickupItemEvent event) {
        trackHopperTransfer(tracking.readAllUnits(event.getItem().getItemStack()), event.getInventory());
    }

    /**
     * Both hopper events fire before the transfer happens and only name the destination
     * inventory, not a slot - scan for the moved unit next tick once it has actually landed.
     * Locates the destination slot using just the first unit as a fingerprint (good enough to
     * find where it landed even if Bukkit cloned the full source list onto it) and records
     * whatever units are on that stack now. Doesn't attempt to also de-duplicate the source stack
     * this was pulled from - see the class-level documented limitation.
     */
    private void trackHopperTransfer(List<UUID> movedUnits, Inventory destination) {
        if (movedUnits.isEmpty()) {
            return;
        }
        UUID fingerprint = movedUnits.get(0);
        Bukkit.getScheduler().runTask(plugin, () -> {
            for (int slot = 0; slot < destination.getSize(); slot++) {
                ItemStack found = destination.getItem(slot);
                if (found == null) {
                    continue;
                }
                List<UUID> units = tracking.readAllUnits(found);
                if (units.contains(fingerprint)) {
                    resolveContext(destination, slot, null, null)
                            .ifPresent(ctx -> tracking.recordLocationForAll(units, ctx, "MOVED", null));
                    return;
                }
            }
        });
    }

    private void reconcileClickResult(Inventory inventory, int slot, Player player, String viewTitle) {
        ItemStack slotItem = inventory.getItem(slot);
        ItemStack cursorItem = player.getItemOnCursor();

        List<ItemStack> affected = new ArrayList<>();
        affected.add(slotItem);
        affected.add(cursorItem);
        tracking.reconcileStacks(affected);
        inventory.setItem(slot, slotItem);
        player.setItemOnCursor(cursorItem);

        if (slotItem == null || slotItem.getType().isAir()) {
            return;
        }
        List<UUID> units = tracking.readAllUnits(slotItem);
        if (units.isEmpty()) {
            return;
        }
        resolveContext(inventory, slot, player, viewTitle)
                .ifPresent(ctx -> tracking.recordLocationForAll(units, ctx, "MOVED", player));
    }

    private void reconcileDragResult(InventoryView view, List<Integer> rawSlots, Player player) {
        List<ItemStack> affected = new ArrayList<>(rawSlots.size());
        for (int rawSlot : rawSlots) {
            affected.add(view.getItem(rawSlot));
        }
        tracking.reconcileStacks(affected);

        String viewTitle = plainTitle(view);
        for (int i = 0; i < rawSlots.size(); i++) {
            int rawSlot = rawSlots.get(i);
            ItemStack item = affected.get(i);
            view.setItem(rawSlot, item);

            if (item == null || item.getType().isAir()) {
                continue;
            }
            List<UUID> units = tracking.readAllUnits(item);
            if (units.isEmpty()) {
                continue;
            }
            Inventory inv = view.getInventory(rawSlot);
            if (inv == null) {
                continue;
            }
            int localSlot = view.convertSlot(rawSlot);
            resolveContext(inv, localSlot, player, viewTitle)
                    .ifPresent(ctx -> tracking.recordLocationForAll(units, ctx, "MOVED", player));
        }
    }

    /**
     * Maps a live Inventory + slot to a {@link LocationContext}. Entity containers are checked
     * before {@link Inventory#getLocation()}, since that method documents itself as returning the
     * location of "the block OR ENTITY" backing the inventory - an entity container would
     * otherwise be misclassified as a block container. Falls back to treating anything else as a
     * generic third-party chest-GUI menu, independent of DisplayGUI (whose menus have no real
     * inventory slots at all, so they never reach this code path in the first place).
     */
    private Optional<LocationContext> resolveContext(Inventory inventory, int slot, Player actor, String viewTitle) {
        InventoryType type = inventory.getType();
        if (type == InventoryType.PLAYER && actor != null) {
            return Optional.of(LocationContext.playerInventory(actor, slot));
        }
        if (type == InventoryType.ENDER_CHEST && actor != null) {
            return Optional.of(LocationContext.enderChest(actor, slot));
        }
        if (type == InventoryType.CRAFTING) {
            return Optional.empty(); // the player's own 2x2 grid, not a real container or menu
        }
        if (inventory.getHolder() instanceof Entity entity) {
            return Optional.of(LocationContext.entityContainer(entity, slot));
        }
        Location location = inventory.getLocation();
        if (location != null) {
            return Optional.of(LocationContext.blockContainer(location, type.name(), slot));
        }
        if (type == InventoryType.SHULKER_BOX) {
            return Optional.empty(); // held shulker - ShulkerNestingListener's job, not ours
        }
        // Anything else with no location and no entity/player/ender-chest identity is a virtual
        // inventory some other plugin created for its own GUI (shop, kit menu, etc.).
        return Optional.of(LocationContext.menu(describeMenu(inventory, viewTitle), slot));
    }

    /** Prefers a custom InventoryHolder's class name (stable identity); falls back to the view's
     * title (works for the common {@code Bukkit.createInventory(null, size, title)} pattern many
     * simple menu plugins use without a custom holder), then the raw InventoryType as a last resort. */
    private static String describeMenu(Inventory inventory, String viewTitle) {
        Object holder = inventory.getHolder();
        if (holder != null) {
            return holder.getClass().getSimpleName();
        }
        if (viewTitle != null && !viewTitle.isBlank()) {
            return viewTitle;
        }
        return inventory.getType().name();
    }

    private static String plainTitle(InventoryView view) {
        return PlainTextComponentSerializer.plainText().serialize(view.title());
    }
}
