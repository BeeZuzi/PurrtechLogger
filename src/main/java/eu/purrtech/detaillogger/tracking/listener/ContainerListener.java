package eu.purrtech.detaillogger.tracking.listener;

import eu.purrtech.detaillogger.tracking.ItemTrackingService;
import eu.purrtech.detaillogger.tracking.LocationContext;
import eu.purrtech.detaillogger.tracking.StackDebug;
import eu.purrtech.detaillogger.tracking.StackMath;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
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
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

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
 * {@link #tryShiftClick} for shift-click's "quick move to the other inventory, topping up existing
 * stacks first". {@link #consolidate} covers what's left - drags, which can still legitimately
 * scatter a tracked stack across several vanilla-chosen slots in one motion - as a general sweep.
 * It's deliberately NOT run after a plain single-slot click any more (see {@link #tryShiftClick}'s
 * Javadoc for the bug that caused: it couldn't tell "vanilla scattered this, re-merge it" apart from
 * "the player just split a stack and put the other half somewhere on purpose").
 */
public final class ContainerListener implements Listener {

    /** Max gap between the two shift-clicks of a shift-double-click. Vanilla's client uses 250 ms;
     * doubled to absorb network jitter between the two click packets. */
    private static final long SHIFT_DOUBLE_CLICK_WINDOW_MS = 500;

    private record TrackedKind(Material type, String templateKey) {
    }

    /** A player's previous click - see {@link #tryShiftDoubleClick}. {@code kind} is the tracked
     * item involved (clicked stack, else cursor), null if none. */
    private record LastClick(Inventory inventory, int slot, long at, TrackedKind kind) {
    }

    private final ItemTrackingService tracking;
    private final Plugin plugin;
    private final Map<UUID, LastClick> lastClicks = new ConcurrentHashMap<>();

    public ContainerListener(ItemTrackingService tracking, Plugin plugin) {
        this.tracking = tracking;
        this.plugin = plugin;
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        if (StackDebug.isEnabled()) {
            StackDebug.log("CLICK " + player.getName() + " click=" + event.getClick() + " action=" + event.getAction()
                    + " slot=" + event.getSlot() + " raw=" + event.getRawSlot()
                    + " inv=" + (event.getClickedInventory() != null ? event.getClickedInventory().getType() : "null")
                    + " slotItem=" + StackDebug.describe(event.getCurrentItem())
                    + " cursor=" + StackDebug.describe(event.getCursor()));
        }
        // Read before any handler below mutates the slot/cursor.
        TrackedKind clickedKind = trackedKind(event.getCurrentItem());
        LastClick previous = lastClicks.put(player.getUniqueId(), new LastClick(event.getClickedInventory(),
                event.getSlot(), System.currentTimeMillis(),
                clickedKind != null ? clickedKind : trackedKind(event.getCursor())));
        boolean handledByHand = tryShiftDoubleClick(event, player, previous);
        if (StackDebug.isEnabled() && handledByHand) {
            StackDebug.log("    -> zpracovano rucne: shift-double-click (event zrusen)");
        }
        if (handledByHand) {
            lastClicks.remove(player.getUniqueId()); // a 3rd quick click starts over, not another bulk move
            return;
        }
        String handler = tryMerge(event, player) ? "tryMerge"
                : tryGatherOntoCursor(event, player) ? "tryGatherOntoCursor"
                : tryShiftClick(event, player) ? "tryShiftClick" : null;
        if (handler != null) {
            StackDebug.log("    -> zpracovano rucne: " + handler + " (event zrusen)"
                    + " slotPo=" + StackDebug.describe(event.getClickedInventory() != null && event.getSlot() >= 0
                            ? event.getClickedInventory().getItem(event.getSlot()) : null)
                    + " cursorPo=" + StackDebug.describe(player.getItemOnCursor()));
            return; // handled entirely by hand (event cancelled) - nothing left to reconcile
        }
        StackDebug.log("    -> nechano vanille (po ticku se jen opravi UUID)");

        Inventory clicked = event.getClickedInventory();
        int slot = event.getSlot();
        InventoryView view = event.getView();
        String viewTitle = plainTitle(view);
        // Scheduled a tick later: at event-dispatch time the click hasn't been resolved by the
        // server yet, so re-reading the slot next tick is the reliable way to see where the item
        // actually ended up, regardless of the exact click type (pickup/place/swap/split).
        // No consolidate() sweep here on purpose: everything that reaches this point is a single,
        // deliberate one-destination action (plain place, plain split, hotbar swap, ...) - see
        // tryShiftClick's Javadoc for why a blanket post-click sweep used to undo exactly that.
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (clicked != null && slot >= 0) {
                reconcileClickResult(clicked, slot, player, viewTitle);
            }
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

        List<UUID> currentUnits = consistentUnits(current);
        List<UUID> cursorUnits = consistentUnits(cursor);
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
        List<UUID> cursorUnits = consistentUnits(cursor);
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
        // Armor/offhand slots are never part of a gather (vanilla doesn't touch them either).
        int limit = clicked instanceof PlayerInventory ? Math.min(36, contents.length) : contents.length;
        int max = cursor.getMaxStackSize();
        List<UUID> gathered = new ArrayList<>(cursorUnits);
        boolean changed = false;
        StackDebug.log("    gather: kurzor=" + StackDebug.describe(cursor) + " slotu=" + limit);

        // Step 1: top the cursor stack up to a full stack from every other same-template stack,
        // front-to-back.
        for (int slot = 0; slot < limit && gathered.size() < max; slot++) {
            ItemStack item = contents[slot];
            if (item == null || item.getType() != cursor.getType()) {
                continue;
            }
            List<UUID> units = consistentUnits(item);
            if (units.isEmpty() || !templateKey.equals(tracking.readTemplateKey(item))) {
                StackDebug.log("        slot " + slot + " preskocen (jiny template / bez UUID): " + StackDebug.describe(item));
                continue;
            }
            int take = Math.min(max - gathered.size(), units.size());
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
            StackDebug.log("        slot " + slot + " -> kurzor +" + take);
            changed = true;
        }

        // Step 2: the cursor is full but fragments are left - glue those into as few stacks as
        // possible ("pokud bude plny tak zase at to jde do noveho stacku") instead of leaving them
        // scattered like vanilla's gather does.
        boolean compacted = compactTemplate(clicked, contents, limit, cursor.getType(), templateKey, player,
                plainTitle(event.getView()));
        changed |= compacted;

        if (!changed) {
            return false;
        }

        event.setCancelled(true);
        clicked.setContents(contents);

        ItemStack gatheredStack = cursor.clone();
        gatheredStack.setAmount(gathered.size());
        tracking.writeMergedUnits(gatheredStack, gathered, templateKey);
        player.setItemOnCursor(gatheredStack);
        StackDebug.log("    gather hotovo: kurzor=" + StackDebug.describe(gatheredStack)
                + (compacted ? " (zbytek slepen do plnych stacku)" : ""));
        // The gathered stack is floating on the cursor, not in a slot - same documented
        // simplification as elsewhere in this class: its location row goes stale until it lands
        // somewhere, which the next click's reconcile/consolidate pass picks up.
        return true;
    }

    /**
     * Re-lays out every stack of one template within {@code contents[0..limit)} into the fewest
     * stacks: pools their units in slot order and refills the SAME slots front-to-back with full
     * stacks, the last one taking the remainder and any slots no longer needed emptied. Only ever
     * touches slots that already held that template, so nothing else moves. Records MERGED for the
     * stacks that changed. Returns false (untouched) if the layout was already compact.
     */
    private boolean compactTemplate(Inventory inventory, ItemStack[] contents, int limit, Material type,
                                    String templateKey, Player player, String viewTitle) {
        List<Integer> slots = new ArrayList<>();
        List<UUID> pool = new ArrayList<>();
        for (int slot = 0; slot < limit; slot++) {
            ItemStack item = contents[slot];
            if (item == null || item.getType() != type) {
                continue;
            }
            List<UUID> units = consistentUnits(item);
            if (units.isEmpty() || !templateKey.equals(tracking.readTemplateKey(item))) {
                continue;
            }
            slots.add(slot);
            pool.addAll(units);
        }
        int max = type.getMaxStackSize();
        int neededSlots = (pool.size() + max - 1) / max;
        if (slots.size() <= neededSlots) {
            return false; // already as compact as it can be (at most one partial stack)
        }

        int next = 0;
        for (int i = 0; i < slots.size(); i++) {
            int slot = slots.get(i);
            if (i >= neededSlots) {
                StackDebug.log("        compact: slot " + slot + " vyprazdnen");
                contents[slot] = null;
                continue;
            }
            int take = Math.min(max, pool.size() - next);
            List<UUID> units = new ArrayList<>(pool.subList(next, next + take));
            next += take;
            ItemStack stack = contents[slot].clone();
            stack.setAmount(units.size());
            tracking.writeMergedUnits(stack, units, templateKey);
            contents[slot] = stack;
            StackDebug.log("        compact: slot " + slot + " = " + units.size());
            resolveContext(inventory, slot, player, viewTitle)
                    .ifPresent(ctx -> tracking.recordLocationForAll(units, ctx, "MERGED", player));
        }
        return true;
    }

    /**
     * Vanilla's shift-click ("quick move") relies on the exact same PDC-similarity check every
     * other shortcut in this class does, so for a tracked stack it can never recognize an existing
     * same-template stack elsewhere as a valid top-up target - it just drops the whole moving stack
     * into the first empty slot it finds, ignoring any partial stack of the same template that's
     * already sitting there. That alone isn't destructive by itself, but leaving shift-click to
     * vanilla and then cleaning up afterward with a blanket {@link #consolidate} sweep of both
     * inventories caused two real bugs: shift-clicking into a chest that already held a partial
     * stack could come out with the existing stack only bumped by one instead of topped up
     * properly, and separately, manually splitting a merged stack and placing the split-off half in
     * its own empty slot got instantly swept back together with the half it came from, undoing the
     * player's own deliberate action, since the sweep couldn't tell that apart from a shift-click's
     * scattered leftovers. Replicating shift-click by hand sidesteps both: transfer into the other
     * inventory's existing same-template stacks first (front-to-back, topping each up to its cap -
     * matching vanilla's own "existing stack first" destination order), then spill whatever's left
     * into empty slots one stack at a time. No more reliance on a broad post-hoc sweep for this
     * case. Returns true if it handled (and cancelled) the event.
     */
    private boolean tryShiftClick(InventoryClickEvent event, Player player) {
        ClickType click = event.getClick();
        if (click != ClickType.SHIFT_LEFT && click != ClickType.SHIFT_RIGHT) {
            return false; // plain clicks/double-clicks are handled elsewhere
        }
        Inventory source = event.getClickedInventory();
        int sourceSlot = event.getSlot();
        if (source == null || sourceSlot < 0) {
            return false;
        }
        ItemStack moving = event.getCurrentItem();
        if (moving == null || moving.getType().isAir()) {
            return false;
        }
        InventoryView view = event.getView();
        Inventory destination = source.equals(view.getTopInventory()) ? view.getBottomInventory() : view.getTopInventory();
        if (!quickMoveStack(source, sourceSlot, destination, player, plainTitle(view))) {
            return false; // untracked, or no room anywhere - vanilla handles/ignores it the same way
        }
        event.setCancelled(true);
        return true;
    }

    /**
     * Vanilla's shift + double-click ("move every item of this kind to the other inventory") is
     * resolved client-side: the client itself picks the matching slots by comparing item
     * components, so for tracked items (unique PDC per unit) it never finds a single match and
     * only the clicked stack moves. The server just sees two shift-clicks on the same slot in
     * quick succession - that's what this detects (see {@link #SHIFT_DOUBLE_CLICK_WINDOW_MS}),
     * then moves every stack of the same template from the clicked inventory to the other one,
     * each via {@link #quickMoveStack}. Covers both ways players do it: shift-double-click on a
     * stack, and shift-double-click with the same item on the cursor (whose first click merges
     * the cursor into the slot - the template is remembered from that click).
     */
    private boolean tryShiftDoubleClick(InventoryClickEvent event, Player player, LastClick previous) {
        ClickType click = event.getClick();
        if (click != ClickType.SHIFT_LEFT && click != ClickType.SHIFT_RIGHT) {
            return false;
        }
        Inventory source = event.getClickedInventory();
        if (source == null || previous == null || previous.slot() != event.getSlot()
                || !source.equals(previous.inventory())
                || System.currentTimeMillis() - previous.at() > SHIFT_DOUBLE_CLICK_WINDOW_MS) {
            return false;
        }
        TrackedKind kind = trackedKind(event.getCurrentItem());
        if (kind == null) {
            kind = previous.kind();
        }
        if (kind == null) {
            return false; // nothing tracked involved - vanilla's own shift-double-click works
        }

        InventoryView view = event.getView();
        Inventory destination = source.equals(view.getTopInventory()) ? view.getBottomInventory() : view.getTopInventory();
        String viewTitle = plainTitle(view);
        ItemStack[] sourceContents = movableContents(source);
        boolean movedAny = false;
        for (int slot = 0; slot < sourceContents.length; slot++) {
            if (kind.equals(trackedKind(sourceContents[slot]))) {
                movedAny |= quickMoveStack(source, slot, destination, player, viewTitle);
            }
        }
        if (!movedAny) {
            return false;
        }
        event.setCancelled(true);
        return true;
    }

    /** Material + template of a tracked stack whose unit count matches its amount, else null. */
    private TrackedKind trackedKind(ItemStack item) {
        // Read-only on purpose (no repair): runs on every click, including on stacks nothing here
        // rewrites - a mismatched unit count still counts as "tracked" for classification.
        if (item == null || item.getType().isAir() || tracking.readAllUnits(item).isEmpty()) {
            return null;
        }
        String templateKey = tracking.readTemplateKey(item);
        return templateKey != null ? new TrackedKind(item.getType(), templateKey) : null;
    }

    /**
     * Quick-moves one tracked stack from {@code source}'s slot into {@code destination} by hand:
     * top up existing same-template stacks first (front-to-back), then spill into empty slots.
     * Updates both inventories and records the location events. Returns false (touching nothing)
     * if the stack isn't tracked or there's no room anywhere.
     */
    private boolean quickMoveStack(Inventory source, int sourceSlot, Inventory destination, Player player,
                                   String viewTitle) {
        ItemStack moving = source.getItem(sourceSlot);
        if (moving == null || moving.getType().isAir()) {
            return false;
        }
        List<UUID> movingUnits = consistentUnits(moving);
        if (movingUnits.isEmpty()) {
            return false;
        }
        String templateKey = tracking.readTemplateKey(moving);
        if (templateKey == null) {
            return false;
        }

        ItemStack[] destContents = movableContents(destination);
        List<UUID> remaining = new ArrayList<>(movingUnits);
        List<Runnable> locationUpdates = new ArrayList<>();
        boolean placedAnything = false;

        // Pass 1: top up existing same-template stacks first, front-to-back.
        for (int slot = 0; slot < destContents.length && !remaining.isEmpty(); slot++) {
            ItemStack existing = destContents[slot];
            if (existing == null || existing.getType() != moving.getType()) {
                continue;
            }
            List<UUID> existingUnits = consistentUnits(existing);
            if (existingUnits.isEmpty() || !templateKey.equals(tracking.readTemplateKey(existing))) {
                continue;
            }
            StackMath.MergeResult sliced = StackMath.mergeUnits(
                    toStrings(existingUnits), toStrings(remaining), existing.getMaxStackSize(), remaining.size());
            if (sliced.destination().size() == existingUnits.size()) {
                continue; // already full
            }
            List<UUID> newUnits = toUuids(sliced.destination());
            ItemStack merged = existing.clone();
            merged.setAmount(newUnits.size());
            tracking.writeMergedUnits(merged, newUnits, templateKey);
            destContents[slot] = merged;
            remaining = new ArrayList<>(toUuids(sliced.source()));
            placedAnything = true;
            int mergedSlot = slot;
            locationUpdates.add(() -> resolveContext(destination, mergedSlot, player, viewTitle)
                    .ifPresent(ctx -> tracking.recordLocationForAll(newUnits, ctx, "MERGED", player)));
        }

        // Pass 2: spill whatever's left into empty slots, one stack per slot up to max stack size.
        for (int slot = 0; slot < destContents.length && !remaining.isEmpty(); slot++) {
            ItemStack existing = destContents[slot];
            if (existing != null && !existing.getType().isAir()) {
                continue;
            }
            int take = Math.min(moving.getMaxStackSize(), remaining.size());
            List<UUID> placedUnits = new ArrayList<>(remaining.subList(0, take));
            ItemStack placed = moving.clone();
            placed.setAmount(placedUnits.size());
            tracking.writeMergedUnits(placed, placedUnits, templateKey);
            destContents[slot] = placed;
            remaining = new ArrayList<>(remaining.subList(take, remaining.size()));
            placedAnything = true;
            int placedSlot = slot;
            locationUpdates.add(() -> resolveContext(destination, placedSlot, player, viewTitle)
                    .ifPresent(ctx -> tracking.recordLocationForAll(placedUnits, ctx, "MOVED", player)));
        }

        if (!placedAnything) {
            return false; // no room anywhere - let vanilla leave it alone, same as it would anyway
        }

        setMovableContents(destination, destContents);

        if (remaining.isEmpty()) {
            source.setItem(sourceSlot, null);
        } else {
            ItemStack remainder = moving.clone();
            remainder.setAmount(remaining.size());
            tracking.writeMergedUnits(remainder, remaining, templateKey);
            source.setItem(sourceSlot, remainder);
        }

        locationUpdates.forEach(Runnable::run);
        return true;
    }

    /** A player inventory's main 36 slots only - never armor/offhand, which a quick-move must
     * not fill (vanilla doesn't either). Any other inventory: all of its slots. */
    private static ItemStack[] movableContents(Inventory inventory) {
        return inventory instanceof PlayerInventory playerInventory
                ? playerInventory.getStorageContents() : inventory.getContents();
    }

    private static void setMovableContents(Inventory inventory, ItemStack[] contents) {
        if (inventory instanceof PlayerInventory playerInventory) {
            playerInventory.setStorageContents(contents);
        } else {
            inventory.setContents(contents);
        }
    }

    /**
     * Units on a stack for hand-built merging, repaired first so the count always equals the
     * stack's amount (see {@code ItemTrackingService#repairUnits}): an untracked stack matching a
     * template is genesis'd, a too-short list (old genesis) topped up, a too-long one (left by a
     * vanilla split) cut. Every merge here sets the result's amount from the unit count, so an
     * unrepaired stack would shrink or refuse to merge. Empty only if the stack can't be tracked at
     * all ("leave it to vanilla"). Mutates the stack passed in - only call it on stacks that get
     * rewritten into a slot/cursor afterward.
     */
    private List<UUID> consistentUnits(ItemStack item) {
        return tracking.repairUnits(item);
    }

    /**
     * Sweeps every slot of the given inventory and merges any tracked stacks that share a
     * template and have room, front-to-back. Only called after a drag now (see {@link #onDrag}) -
     * shift-click and direct-click merging are handled by hand ({@link #tryShiftClick},
     * {@link #tryMerge}, {@link #tryGatherOntoCursor}) precisely because a blanket sweep like this
     * one can't distinguish "vanilla scattered this, re-merge it" from "the player deliberately put
     * these in separate slots" - it used to run after every click for exactly that reason and that
     * caused a real bug (see {@link #tryShiftClick}'s Javadoc). A drag is still a single gesture
     * spread across several vanilla-picked slots in one go rather than several independent player
     * choices, so sweeping it afterward remains safe. Idempotent (a second call on an
     * already-consolidated inventory is a no-op) - a single greedy left-to-right pass may leave a
     * little fragmentation in rare cases (e.g. three-way splits), which the very next sweep mops up.
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
            List<UUID> units = consistentUnits(item);
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
            List<UUID> targetUnits = consistentUnits(target);
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
        if (StackDebug.isEnabled()) {
            StackDebug.log("DRAG " + player.getName() + " type=" + event.getType() + " rawSlots=" + rawSlots
                    + " cursorPred=" + StackDebug.describe(event.getOldCursor())
                    + " cursorPo=" + StackDebug.describe(event.getCursor()) + " cancelled=" + event.isCancelled());
            event.getNewItems().forEach((rawSlot, item) ->
                    StackDebug.log("    vanilla dava slot " + rawSlot + ": " + StackDebug.describe(item)));
        }
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
        if (StackDebug.isEnabled()) {
            StackDebug.log("    reconcile klik (po ticku) PRED: slot " + slot + "=" + StackDebug.describe(slotItem)
                    + " cursor=" + StackDebug.describe(cursorItem));
        }
        tracking.reconcileStacks(affected);
        inventory.setItem(slot, slotItem);
        player.setItemOnCursor(cursorItem);
        if (StackDebug.isEnabled()) {
            StackDebug.log("    reconcile klik PO:   slot " + slot + "=" + StackDebug.describe(slotItem)
                    + " cursor=" + StackDebug.describe(cursorItem));
        }

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
        // The stack still on the cursor after the drag is part of the same split: vanilla cloned the
        // full UUID list onto it too, so leaving it out of the reslice kept a too-long list on it
        // (and let the slots run out of UUIDs). Last in the list = it gets whatever the slots
        // didn't take.
        ItemStack cursor = player.getItemOnCursor();
        affected.add(cursor);
        if (StackDebug.isEnabled()) {
            StackDebug.log("    reconcile drag PRED (po ticku):");
            for (int i = 0; i < rawSlots.size(); i++) {
                StackDebug.log("        raw " + rawSlots.get(i) + ": " + StackDebug.describe(affected.get(i)));
            }
            StackDebug.log("        cursor: " + StackDebug.describe(cursor));
        }
        tracking.reconcileStacks(affected);
        if (StackDebug.isEnabled()) {
            StackDebug.log("    reconcile drag PO:");
            for (int i = 0; i < rawSlots.size(); i++) {
                StackDebug.log("        raw " + rawSlots.get(i) + ": " + StackDebug.describe(affected.get(i)));
            }
            StackDebug.log("        cursor: " + StackDebug.describe(cursor));
        }
        affected.remove(affected.size() - 1);
        player.setItemOnCursor(cursor);

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

        // No consolidate() here on purpose. A drag IS the player deliberately spreading a stack over
        // several slots, so sweeping both inventories afterward merged the pieces straight back
        // into one stack - and, since the sweep covers the whole top inventory too, also into
        // unrelated same-template stacks in the open chest.
        StackDebug.dumpView("po dragu - stav", view);
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
