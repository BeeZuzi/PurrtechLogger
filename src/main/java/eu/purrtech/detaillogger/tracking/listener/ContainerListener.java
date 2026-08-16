package eu.purrtech.detaillogger.tracking.listener;

import eu.purrtech.detaillogger.tracking.ItemTrackingService;
import eu.purrtech.detaillogger.tracking.LocationContext;
import eu.purrtech.detaillogger.tracking.StackMath;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
 * <p>
 * Two physically identical tracked stacks (same template, different UUID) never look "similar" to
 * Bukkit - every unit's PDC is unique by design - so vanilla can never stack, gather, or quick-move
 * them into each other on its own. Every place a vanilla shortcut would normally rely on that
 * similarity check is handled here by hand instead: {@link #tryMerge} for a direct click onto an
 * existing stack (now with proper partial-merge support - see {@link StackMath#mergeUnits}, fixing
 * an earlier bug where a stack that didn't fully fit fell through to vanilla's swap and looked like
 * it vanished), {@link #tryGatherOntoCursor} for double-click's "collect all matching items", and
 * {@link #consolidate} for everything else (shift-click, hopper transfers, drags) - run as a
 * general sweep after any inventory action instead of trying to replicate each shortcut's own
 * destination-selection rules one by one.
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
        if (tryMerge(event, player) || tryGatherOntoCursor(event, player)) {
            return; // handled entirely by hand (event cancelled) - nothing left to reconcile
        }

        Inventory clicked = event.getClickedInventory();
        int slot = event.getSlot();
        InventoryView view = event.getView();
        String viewTitle = plainTitle(view);
        // Scheduled a tick later: at event-dispatch time the click hasn't been resolved by the
        // server yet, so re-reading the slot next tick is the reliable way to see where the item
        // actually ended up, regardless of the exact click type (pickup/place/swap/shift-click).
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (clicked != null && slot >= 0) {
                reconcileClickResult(clicked, slot, player, viewTitle);
            }
            // Shift-click (and anything else not handled above) can scatter a tracked stack
            // across whichever slots vanilla's own algorithm picked in the OTHER inventory of
            // this view, not just the clicked one - sweep both sides to pull any same-template
            // fragments back together instead of trying to predict where vanilla put them.
            consolidate(view.getTopInventory(), player, viewTitle);
            consolidate(view.getBottomInventory(), player, viewTitle);
        });
    }

    /**
     * Vanilla refuses to auto-combine two stacks whose PDC differs (every tracked unit's is
     * unique), even when the player's intent - left/right-clicking one tracked stack onto another
     * of the same template - is clearly to merge them; left alone it would swap them instead, and
     * if the source stack didn't fully fit, that swap looked to the player like the rest of it
     * just vanished (it didn't - it landed on the cursor - but nothing said so). This builds the
     * merge by hand instead, moving as many units as actually fit: a left-click moves the whole
     * source stack (whatever doesn't fit stays on the cursor), a right-click moves exactly one
     * (matching vanilla's own place-one semantics for a stackable target). Returns true if it
     * handled (and cancelled) the event.
     */
    private boolean tryMerge(InventoryClickEvent event, Player player) {
        ClickType click = event.getClick();
        if (click != ClickType.LEFT && click != ClickType.RIGHT) {
            return false; // shift-click/double-click/etc. are handled elsewhere
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

        int transferLimit = click == ClickType.LEFT ? cursorUnits.size() : 1;
        StackMath.MergeResult sliced = StackMath.mergeUnits(
                toStrings(currentUnits), toStrings(cursorUnits), current.getMaxStackSize(), transferLimit);
        if (sliced.destination().size() == currentUnits.size()) {
            return false; // no room at all - let vanilla do its (safe) swap instead
        }

        event.setCancelled(true);

        List<UUID> newCurrentUnits = toUuids(sliced.destination());
        ItemStack mergedStack = current.clone();
        mergedStack.setAmount(newCurrentUnits.size());
        tracking.writeMergedUnits(mergedStack, newCurrentUnits, currentTemplate);
        clicked.setItem(event.getSlot(), mergedStack);

        List<UUID> remainingCursorUnits = toUuids(sliced.source());
        if (remainingCursorUnits.isEmpty()) {
            player.setItemOnCursor(null);
        } else {
            ItemStack cursorRemainder = cursor.clone();
            cursorRemainder.setAmount(remainingCursorUnits.size());
            tracking.writeMergedUnits(cursorRemainder, remainingCursorUnits, currentTemplate);
            player.setItemOnCursor(cursorRemainder);
        }

        resolveContext(clicked, event.getSlot(), player, plainTitle(event.getView()))
                .ifPresent(ctx -> tracking.recordLocationForAll(newCurrentUnits, ctx, "MERGED", player));
        return true;
    }

    /**
     * Vanilla's double-click "collect all matching items onto the cursor" relies on the exact
     * same similarity check that blocks every other vanilla shortcut here, so it silently finds
     * nothing to collect for tracked stacks. This replicates it by hand: gather units from the
     * clicked inventory's own slots (matching the rest of this class's documented per-inventory
     * scope) onto the cursor stack up to its max size, front-to-back.
     */
    private boolean tryGatherOntoCursor(InventoryClickEvent event, Player player) {
        if (event.getClick() != ClickType.DOUBLE_CLICK) {
            return false;
        }
        ItemStack cursor = event.getCursor();
        if (cursor == null || cursor.getType().isAir()) {
            return false;
        }
        List<UUID> cursorUnits = tracking.readAllUnits(cursor);
        if (cursorUnits.isEmpty()) {
            return false; // untracked - vanilla's own gather already works fine for it
        }
        String templateKey = tracking.readTemplateKey(cursor);
        if (templateKey == null) {
            return false;
        }
        Inventory clicked = event.getClickedInventory();
        if (clicked == null) {
            return false;
        }

        ItemStack[] contents = clicked.getContents();
        List<UUID> gathered = new ArrayList<>(cursorUnits);
        boolean changed = false;

        for (int slot = 0; slot < contents.length && gathered.size() < cursor.getMaxStackSize(); slot++) {
            ItemStack item = contents[slot];
            if (item == null || item.getType() != cursor.getType()) {
                continue;
            }
            List<UUID> units = tracking.readAllUnits(item);
            if (units.isEmpty() || !templateKey.equals(tracking.readTemplateKey(item))) {
                continue;
            }
            int take = Math.min(cursor.getMaxStackSize() - gathered.size(), units.size());
            gathered.addAll(units.subList(0, take));
            List<UUID> remaining = units.subList(take, units.size());
            if (remaining.isEmpty()) {
                contents[slot] = null;
            } else {
                ItemStack remainder = item.clone();
                remainder.setAmount(remaining.size());
                tracking.writeMergedUnits(remainder, remaining, templateKey);
                contents[slot] = remainder;
            }
            changed = true;
        }

        if (!changed) {
            return false;
        }

        event.setCancelled(true);
        clicked.setContents(contents);

        ItemStack gatheredStack = cursor.clone();
        gatheredStack.setAmount(gathered.size());
        tracking.writeMergedUnits(gatheredStack, gathered, templateKey);
        player.setItemOnCursor(gatheredStack);
        // The gathered stack is floating on the cursor, not in a slot - same documented
        // simplification as elsewhere in this class: its location row goes stale until it lands
        // somewhere, which the next click's reconcile/consolidate pass picks up.
        return true;
    }

    /**
     * Sweeps every slot of the given inventory and merges any tracked stacks that share a
     * template and have room, front-to-back. The general fix for every vanilla shortcut that can
     * leave the same tracked template split across multiple slots - shift-click, hopper
     * transfers, drags - without needing to replicate each one's own destination-selection rules;
     * it just cleans up wherever vanilla put things. Idempotent (a second call on an
     * already-consolidated inventory is a no-op) and cheap enough to run after every inventory
     * action - a single greedy left-to-right pass may leave a little fragmentation in rare cases
     * (e.g. three-way splits), which the very next action's sweep mops up.
     */
    private void consolidate(Inventory inventory, Player actor, String viewTitle) {
        ItemStack[] contents = inventory.getContents();
        Map<String, Integer> mergeTargetBySlotKey = new HashMap<>();
        boolean changed = false;

        for (int slot = 0; slot < contents.length; slot++) {
            ItemStack item = contents[slot];
            if (item == null || item.getType().isAir()) {
                continue;
            }
            List<UUID> units = tracking.readAllUnits(item);
            if (units.isEmpty()) {
                continue;
            }
            String templateKey = tracking.readTemplateKey(item);
            if (templateKey == null) {
                continue;
            }
            String groupKey = item.getType().name() + ":" + templateKey;
            Integer targetSlot = mergeTargetBySlotKey.get(groupKey);
            if (targetSlot == null) {
                mergeTargetBySlotKey.put(groupKey, slot);
                continue;
            }

            ItemStack target = contents[targetSlot];
            List<UUID> targetUnits = tracking.readAllUnits(target);
            StackMath.MergeResult sliced = StackMath.mergeUnits(
                    toStrings(targetUnits), toStrings(units), target.getMaxStackSize(), units.size());
            if (sliced.destination().size() == targetUnits.size()) {
                // target's already full - this stack becomes the new merge point for anything after it
                mergeTargetBySlotKey.put(groupKey, slot);
                continue;
            }

            List<UUID> newTargetUnits = toUuids(sliced.destination());
            ItemStack mergedTarget = target.clone();
            mergedTarget.setAmount(newTargetUnits.size());
            tracking.writeMergedUnits(mergedTarget, newTargetUnits, templateKey);
            contents[targetSlot] = mergedTarget;

            List<UUID> remaining = toUuids(sliced.source());
            if (remaining.isEmpty()) {
                contents[slot] = null;
            } else {
                ItemStack remainder = item.clone();
                remainder.setAmount(remaining.size());
                tracking.writeMergedUnits(remainder, remaining, templateKey);
                contents[slot] = remainder;
            }

            resolveContext(inventory, targetSlot, actor, viewTitle)
                    .ifPresent(ctx -> tracking.recordLocationForAll(newTargetUnits, ctx, "MERGED", actor));
            changed = true;
        }

        if (changed) {
            inventory.setContents(contents);
        }
    }

    private static List<String> toStrings(List<UUID> uuids) {
        return uuids.stream().map(UUID::toString).toList();
    }

    private static List<UUID> toUuids(List<String> strings) {
        return strings.stream().map(UUID::fromString).toList();
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

        // A drag can spread a stack across several slots that each already held some of the same
        // template - same fragmentation problem as shift-click, same fix.
        consolidate(view.getTopInventory(), player, viewTitle);
        consolidate(view.getBottomInventory(), player, viewTitle);
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
