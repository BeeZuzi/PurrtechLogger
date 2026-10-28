package eu.purrtech.detaillogger.tracking;

import eu.purrtech.detaillogger.db.dao.EventDao;
import eu.purrtech.detaillogger.db.dao.LineageDao;
import eu.purrtech.detaillogger.db.dao.LocationDao;
import eu.purrtech.detaillogger.db.dao.TrackedUnitDao;
import eu.purrtech.detaillogger.template.TemplateDefinition;
import eu.purrtech.detaillogger.template.TemplateMatcher;
import eu.purrtech.detaillogger.template.TemplateRegistry;
import eu.purrtech.detaillogger.tracking.pdc.TrackedItemTag;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Item lifecycle operations shared by every tracking listener: genesis, location updates and
 * destruction. Item-only for now - the block equivalent lands with block tracking.
 */
public final class ItemTrackingService {

    private final TrackedItemTag itemTag;
    private final TemplateMatcher matcher;
    private final TemplateRegistry registry;
    private final TrackedUnitDao trackedUnitDao;
    private final LocationDao locationDao;
    private final EventDao eventDao;
    private final LineageDao lineageDao;
    private final LocationRecorder locationRecorder;
    private final StackReconciler stackReconciler;
    private final Logger logger;

    public ItemTrackingService(TrackedItemTag itemTag, TemplateMatcher matcher, TemplateRegistry registry,
                                TrackedUnitDao trackedUnitDao, LocationDao locationDao, EventDao eventDao,
                                LineageDao lineageDao, Logger logger) {
        this.itemTag = itemTag;
        this.matcher = matcher;
        this.registry = registry;
        this.trackedUnitDao = trackedUnitDao;
        this.locationDao = locationDao;
        this.eventDao = eventDao;
        this.lineageDao = lineageDao;
        this.locationRecorder = new LocationRecorder(locationDao, eventDao);
        this.stackReconciler = new StackReconciler(itemTag);
        this.logger = logger;
    }

    /**
     * A one-piece copy of the item without this plugin's tags (UUID, UUID list, template key) - what a
     * template stores as "the item", so that handing it out never duplicates a UUID. Tagged items
     * inside a shulker box or bundle are not looked at.
     */
    public ItemStack untaggedSample(ItemStack item) {
        ItemStack copy = item.clone();
        copy.setAmount(1);
        itemTag.strip(copy);
        return copy;
    }

    /**
     * Whether two stacks may share one slot: same material, same template, and identical in every
     * way (name, lore, enchants, other plugins' data...) EXCEPT this plugin's own tracking tags.
     * Vanilla can't stack tracked items at all, since every unit's tag differs - so every hand-built
     * merge has to ask this instead. A renamed diamond must never merge into a plain one.
     */
    public boolean sameStack(ItemStack a, ItemStack b) {
        if (a == null || b == null || a.getType() != b.getType()) {
            return false;
        }
        String templateA = itemTag.readTemplateKey(a);
        if (templateA == null || !templateA.equals(itemTag.readTemplateKey(b))) {
            return false;
        }
        ItemStack strippedA = a.clone();
        ItemStack strippedB = b.clone();
        itemTag.strip(strippedA);
        itemTag.strip(strippedB);
        strippedA.setAmount(1);
        strippedB.setAmount(1);
        return strippedA.isSimilar(strippedB);
    }

    /** A copy with the tracking tags removed and amount 1 - the reference for {@link #sameStack}. */
    public ItemStack stackSample(ItemStack item) {
        ItemStack sample = item.clone();
        sample.setAmount(1);
        return sample;
    }

    /**
     * An item came out of a transformation (anvil rename, enchant, grindstone, smithing...) carrying
     * its input's tracking tag, i.e. the very same UUIDs. A modified item is a new thing: give the
     * result fresh UUIDs (one per item, same template) and record parent -> child for every one,
     * so each unit's profile shows what it was made from. Mutates {@code result}'s meta in place.
     * Returns the new units (empty if the result carries no tag, or its template has no DB id yet).
     */
    public List<UUID> deriveUnits(ItemStack result, List<UUID> parents, String relation) {
        String templateKey = itemTag.readTemplateKey(result);
        Integer templateId = templateKey != null ? registry.idOf(templateKey) : null;
        if (templateId == null || parents.isEmpty()) {
            return List.of();
        }
        List<UUID> children = mintUnits(templateId, result.getAmount(), "DERIVED");
        itemTag.writeUnits(result, children, templateKey);
        long now = System.currentTimeMillis();
        for (int i = 0; i < children.size(); i++) {
            lineageDao.enqueue(parents.get(i % parents.size()).toString(), children.get(i).toString(),
                    relation, null, now);
        }
        return children;
    }

    /**
     * Units that were used up by a transformation whose result is not tracked (e.g. diamonds crafted
     * into a block): they are gone, so mark them destroyed with the cause and link them to what they
     * became in {@code detail}.
     */
    public void consumeUnits(List<UUID> units, String relation, String detail, Location where, Player actor) {
        long now = System.currentTimeMillis();
        for (UUID unit : units) {
            lineageDao.enqueue(unit.toString(), null, relation, detail, now);
        }
        markDestroyedForAll(units, relation, where, actor);
    }

    /** Parents whose children were derived by {@link #deriveUnits}: used up, so no longer alive. */
    public void retireUnits(List<UUID> units, String cause, Location where, Player actor) {
        markDestroyedForAll(units, cause, where, actor);
    }

    /** Cheap pre-check: is there any item template for this material at all? Lets hot paths (every
     * inventory slot change) skip the expensive item-meta match for everything else. */
    public boolean mayBeTemplate(org.bukkit.Material type) {
        for (TemplateDefinition definition : registry.definitions()) {
            if (definition.trackItems() && !definition.ledger() && definition.material() == type) {
                return true;
            }
        }
        return false;
    }

    /** Same cheap pre-check for {@code mode: ledger} templates. */
    public boolean mayBeLedger(org.bukkit.Material type) {
        for (TemplateDefinition definition : registry.definitions()) {
            if (definition.trackItems() && definition.ledger() && definition.material() == type) {
                return true;
            }
        }
        return false;
    }

    /** The ledger template this stack belongs to, if any. */
    public Optional<TemplateDefinition> ledgerTemplate(ItemStack item) {
        if (item == null || item.getType().isAir() || !mayBeLedger(item.getType())) {
            return Optional.empty();
        }
        return matcher.match(item).filter(TemplateDefinition::ledger);
    }

    // === Reference items ===========================================================================
    // The master copy of an item a crate (or any plugin) hands out. It has a UUID, the DB marks it as
    // a reference (tracked_units.origin = REFERENCE), and whenever a COPY of it shows up anywhere the
    // copy is given fresh UUIDs of its own, linked to the master in unit_lineage (REFERENCE_COPY).

    public static final String ORIGIN_REFERENCE = "REFERENCE";
    private static final long REFERENCE_SESSION_MS = 10 * 60_000L;

    private final Set<UUID> references = ConcurrentHashMap.newKeySet();
    /** Reference UUID -> until when its owner is still setting it up (see {@link #createReference}). */
    private final Map<UUID, Long> referenceSessions = new ConcurrentHashMap<>();

    /** Loaded from the DB at startup (async) - which units are references. */
    public void loadReferences(java.util.Collection<UUID> loaded) {
        references.addAll(loaded);
    }

    public boolean isReference(UUID unit) {
        return references.contains(unit);
    }

    public boolean carriesReference(List<UUID> units) {
        for (UUID unit : units) {
            if (references.contains(unit)) {
                return true;
            }
        }
        return false;
    }

    private boolean referenceSessionActive(List<UUID> units) {
        long now = System.currentTimeMillis();
        for (UUID unit : units) {
            Long until = referenceSessions.get(unit);
            if (until != null && until > now) {
                return true;
            }
        }
        return false;
    }

    /**
     * Makes the held item a reference. Exactly one item, matching a unit-mode template. It is tagged with
     * a fresh UUID and registered as a reference, and a SESSION opens for 10 minutes: while it is open the
     * master (and copies) are left alone, so the owner can put it into the crate's config without it being
     * re-labelled on the spot. {@link #closeReferenceSessions} (or the timeout) ends it. Mutates
     * {@code held}'s meta in place - the caller writes it back. Empty if the item doesn't qualify.
     */
    public Optional<UUID> createReference(ItemStack held, Location where, Player owner) {
        if (held == null || held.getType().isAir() || held.getAmount() != 1) {
            return Optional.empty();
        }
        Optional<TemplateDefinition> match = matcher.match(held);
        if (match.isEmpty() || match.get().ledger() || registry.idOf(match.get().key()) == null) {
            return Optional.empty();
        }
        long now = System.currentTimeMillis();
        List<UUID> existing = itemTag.readUnits(held);
        UUID reference;
        if (existing.size() == 1 && references.contains(existing.get(0))) {
            reference = existing.get(0); // already the master - just reopen the session
        } else {
            if (!existing.isEmpty()) {
                markDestroyedForAll(existing, "REFERENCE_REPLACED", where, owner); // it had an ordinary unit
            }
            reference = UUID.randomUUID();
            itemTag.writeSingleUnit(held, reference, match.get().key());
            trackedUnitDao.enqueueUpsert(reference.toString(), registry.idOf(match.get().key()), "ITEM",
                    ORIGIN_REFERENCE, null, now, true, null, null);
            eventDao.enqueue(reference.toString(), "GENESIS", now, null, null, null, null, null,
                    jsonField("origin", ORIGIN_REFERENCE));
            references.add(reference);
        }
        referenceSessions.put(reference, now + REFERENCE_SESSION_MS);
        return Optional.of(reference);
    }

    public void closeReferenceSessions() {
        referenceSessions.clear();
    }

    private List<UUID> deriveFromReference(ItemStack item, List<UUID> raw) {
        List<UUID> masters = new ArrayList<>();
        for (UUID unit : raw) {
            if (references.contains(unit)) {
                masters.add(unit);
            }
        }
        List<UUID> copies = deriveUnits(item, masters, "REFERENCE_COPY");
        return copies.isEmpty() ? raw : copies; // template not in the DB yet -> try again next time
    }

    // === Items that vanished right after a command ================================================
    // A command (an auction listing, a mail, a trade...) can take an item out of the inventory with no
    // click and no event. When one does and the unit turns up nowhere else, it is booked as VANISHED_BY_
    // COMMAND with the command. If it later shows up again, that is REAPPEARED_AFTER_COMMAND - noted,
    // and explicitly NOT a duplicate.

    private final Map<UUID, String> vanishedByCommand = new ConcurrentHashMap<>();

    /** Loaded from the DB at startup (async): units whose last location is COMMAND_VANISHED. */
    public void loadVanished(Map<UUID, String> loaded) {
        vanishedByCommand.putAll(loaded);
    }

    public boolean anyVanished(List<UUID> units) {
        for (UUID unit : units) {
            if (vanishedByCommand.containsKey(unit)) {
                return true;
            }
        }
        return false;
    }

    /** {@code unit} left {@code actor}'s inventory during {@code command} and was found nowhere else. */
    public void markVanished(UUID unit, Player actor, String command) {
        long now = System.currentTimeMillis();
        Location loc = actor.getLocation();
        String world = loc.getWorld() != null ? loc.getWorld().getName() : null;
        locationDao.enqueueUpsert(unit.toString(), "COMMAND_VANISHED", actor.getUniqueId().toString(), null, world,
                loc.getBlockX(), loc.getBlockY(), loc.getBlockZ(), null, null, null, command, now);
        eventDao.enqueue(unit.toString(), "VANISHED_BY_COMMAND", now, world, loc.getBlockX(), loc.getBlockY(),
                loc.getBlockZ(), actor.getUniqueId().toString(), jsonField("command", command),
                actor.getGameMode().name(), null);
        if (vanishedByCommand.size() < 50_000) {
            vanishedByCommand.put(unit, command);
        }
    }

    private void noteReappearance(UUID uuid, LocationContext ctx, Player actor) {
        String command = vanishedByCommand.remove(uuid);
        if (command == null) {
            return;
        }
        eventDao.enqueue(uuid.toString(), "REAPPEARED_AFTER_COMMAND", System.currentTimeMillis(), ctx.world(),
                ctx.x(), ctx.y(), ctx.z(), actor != null ? actor.getUniqueId().toString() : null,
                jsonField("vanished_by", command), null, null);
    }

    public boolean isTracked(ItemStack item) {
        return itemTag.isTracked(item);
    }

    /**
     * Repairs a set of stacks affected by the same inventory action in case Bukkit naively cloned
     * a tracked unit list onto more than one of them (e.g. right-click take-half copies NBT
     * verbatim to both halves). See {@link StackReconciler}. No-ops if nothing needs fixing.
     */
    public void reconcileStacks(List<ItemStack> affectedStacks) {
        backfillSplitShortfall(affectedStacks);
        stackReconciler.reconcile(affectedStacks);
    }

    /**
     * A split (drag, right-click take-half) clones one UUID list onto several stacks. If that list
     * is shorter than the total amount now spread across them (a stack from the old
     * one-UUID-per-stack genesis), reslicing alone would run out of UUIDs and strip the last
     * stacks - so mint the missing units first and put them on the first stack, where the reslice
     * picks them up. Only acts when a UUID really appears on more than one stack (= a clone).
     */
    private void backfillSplitShortfall(List<ItemStack> stacks) {
        java.util.Set<UUID> pool = new java.util.LinkedHashSet<>();
        int listed = 0;
        int total = 0;
        ItemStack first = null;
        for (ItemStack stack : stacks) {
            if (stack == null || stack.getType().isAir()) {
                continue;
            }
            List<UUID> units = itemTag.readUnits(stack);
            if (units.isEmpty()) {
                continue;
            }
            pool.addAll(units);
            listed += units.size();
            total += stack.getAmount();
            if (first == null) {
                first = stack;
            }
        }
        int shortfall = total - pool.size();
        if (first == null || listed == pool.size() || shortfall <= 0) {
            return; // no clone involved, or the list already covers every item
        }
        String templateKey = itemTag.readTemplateKey(first);
        Integer templateId = templateKey != null ? registry.idOf(templateKey) : null;
        if (templateId == null) {
            return;
        }
        List<UUID> merged = new ArrayList<>(itemTag.readUnits(first));
        merged.addAll(mintUnits(templateId, shortfall, "STACK_BACKFILL"));
        itemTag.writeUnits(first, merged, templateKey);
    }

    public String readTemplateKey(ItemStack item) {
        return itemTag.readTemplateKey(item);
    }

    /**
     * Writes an explicitly-combined unit list onto a stack - used when two same-template tracked
     * stacks are merged by direct plugin intervention (vanilla refuses to auto-merge stacks whose
     * PDC differs, so a same-template combine has to be built by hand). Caller is responsible for
     * placing the resulting stack and clearing whatever it was merged from.
     */
    public void writeMergedUnits(ItemStack destination, List<UUID> mergedUnits, String templateKey) {
        itemTag.writeUnits(destination, mergedUnits, templateKey);
    }

    public UUID readTrackedUuid(ItemStack item) {
        return itemTag.readUuid(item);
    }

    /**
     * Every unit currently represented by this stack, whether it's a lone item (one UUID) or a
     * merged stack (several). Empty if untracked. Listeners that treat "the whole stack moved
     * together" (ground drop/pickup, container placement, destruction) loop over this instead of
     * assuming a single unit.
     */
    public List<UUID> readAllUnits(ItemStack item) {
        return itemTag.readUnits(item);
    }

    /**
     * Ensures the item is tracked: tags an untracked template-matching item as a new genesis, or
     * returns the UUID of an already-tracked one. Empty if the item matches no template and isn't
     * already tracked. Mutates the passed ItemStack's meta in place on genesis - the caller is
     * responsible for writing it back to wherever it came from (event.setItemStack, inventory
     * slot, etc.) since Bukkit doesn't always hand out a live reference.
     */
    public Optional<UUID> ensureTracked(ItemStack item, String origin) {
        if (itemTag.isTracked(item)) {
            // Already tracked - if it's a merged stack (list-tagged), there's no single UUID to
            // hand back; callers dealing with possibly-merged stacks should use
            // ensureTrackedAll instead, which never triggers the genesis path below.
            return Optional.ofNullable(itemTag.readUuid(item));
        }

        Optional<TemplateDefinition> match = matcher.match(item);
        if (match.isEmpty()) {
            return Optional.empty();
        }

        TemplateDefinition def = match.get();
        if (def.ledger()) {
            return Optional.empty(); // counted by LedgerService, never tagged - it must stack like vanilla
        }
        Integer templateId = registry.idOf(def.key());
        if (templateId == null) {
            logger.warning("Sablona '" + def.key() + "' jeste nema potvrzene id v DB, genesis odlozena do dalsi observace");
            return Optional.empty();
        }

        // One unit per physical item, not one per stack - every hand-built merge/split treats
        // units.size() as the stack's real amount (see StackMath), so a 64-stack tagged with a
        // single UUID used to collapse to 1 item the first time it was merged.
        List<UUID> units = mintUnits(templateId, Math.max(1, item.getAmount()), origin);
        itemTag.writeUnits(item, units, def.key());
        return Optional.of(units.get(0));
    }

    /**
     * Returns every unit on this stack, first topping it up to one unit per physical item if it
     * carries fewer UUIDs than its amount - stacks genesis'd before genesis minted per item carry a
     * single UUID for a whole stack, which every hand-built merge would read as a 1-item stack and
     * silently shrink. Missing units are minted as fresh genesis ({@code origin=STACK_BACKFILL}) and
     * written onto the stack in place; the caller must write the stack back to its slot if it isn't
     * a live reference. Untracked stacks, or ones whose template has no DB id yet, are returned
     * unchanged.
     */
    public List<UUID> readAllUnitsNormalized(ItemStack item) {
        List<UUID> units = itemTag.readUnits(item);
        int missing = item.getAmount() - units.size();
        if (units.isEmpty() || missing <= 0) {
            return units;
        }
        String templateKey = itemTag.readTemplateKey(item);
        Integer templateId = templateKey != null ? registry.idOf(templateKey) : null;
        if (templateId == null) {
            return units;
        }
        List<UUID> normalized = new ArrayList<>(units);
        normalized.addAll(mintUnits(templateId, missing, "STACK_BACKFILL"));
        itemTag.writeUnits(item, normalized, templateKey);
        return normalized;
    }

    private List<UUID> mintUnits(int templateId, int count, String origin) {
        long now = System.currentTimeMillis();
        List<UUID> units = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            UUID uuid = UUID.randomUUID();
            units.add(uuid);
            trackedUnitDao.enqueueUpsert(uuid.toString(), templateId, "ITEM", origin, null, now, true, null, null);
            eventDao.enqueue(uuid.toString(), "GENESIS", now, null, null, null, null, null, jsonField("origin", origin));
        }
        return units;
    }

    /**
     * Stack-aware version of {@link #ensureTracked}: an already-tracked stack (scalar or merged
     * list) yields every unit it currently holds, an untracked template-matching item genesis'd
     * as a fresh single unit yields a singleton list, and a non-matching untracked item yields an
     * empty list. This is the entry point every location/observation listener should use instead
     * of {@link #ensureTracked} once merged stacks are possible.
     */
    public List<UUID> ensureTrackedAll(ItemStack item, String origin) {
        // An item carrying a REFERENCE unit's UUID is a copy of the master (e.g. handed out by a crate
        // that stores the master in its config): every copy becomes its own new unit. Checked before the
        // repair below, which would otherwise "top up" a stack that carries just the one master UUID.
        List<UUID> raw = itemTag.readUnits(item);
        if (carriesReference(raw)) {
            return referenceSessionActive(raw) ? raw : deriveFromReference(item, raw);
        }
        // Every caller writes the item back afterward, so this is also where stacks from the old
        // one-UUID-per-stack genesis get repaired (chest open, join scan, pickup, ...).
        List<UUID> existing = readAllUnitsNormalized(item);
        if (!existing.isEmpty()) {
            return existing;
        }
        if (ensureTracked(item, origin).isEmpty()) {
            return List.of();
        }
        // Genesis tags one unit per physical item now - return all of them, not just the first.
        return itemTag.readUnits(item);
    }

    /**
     * Makes a stack's unit list match its amount so it can be merged by hand, repairing whatever
     * is off: an untracked stack that matches a template gets genesis'd, a stack with too few
     * UUIDs (old one-UUID-per-stack genesis) is topped up, one with too many (vanilla cloned a
     * longer list onto a smaller split-off stack) is cut to its amount. Mutates the stack's meta
     * in place - callers only use this on stacks they rewrite into a slot/cursor anyway. Empty if
     * the stack isn't (and can't become) tracked.
     */
    public List<UUID> repairUnits(ItemStack item) {
        List<UUID> units = ensureTrackedAll(item, "IMPORTED");
        if (units.isEmpty()) {
            return units;
        }
        int amount = item.getAmount();
        if (units.size() > amount) {
            units = new ArrayList<>(units.subList(0, amount));
            itemTag.writeUnits(item, units, itemTag.readTemplateKey(item));
        }
        return units.size() == amount ? units : List.of();
    }

    /**
     * Ensures the given shulker box item has a "container anchor" identity - a synthetic tracked
     * unit that exists purely to be a {@code parent_shulker_uuid} FK target for tracked items
     * nested inside it, independent of whether the shulker itself matches any user template.
     * Bypasses {@link TemplateMatcher} entirely (the anchor template is registered with
     * {@code track-items=false} so it never matches via the normal genesis path). Mutates the
     * item's meta in place - same write-back contract as {@link #ensureTracked}.
     */
    public Optional<UUID> ensureContainerAnchor(ItemStack shulkerItem) {
        UUID existing = itemTag.readUuid(shulkerItem);
        if (existing != null) {
            return Optional.of(existing);
        }

        Integer anchorTemplateId = registry.idOf(TemplateRegistry.SHULKER_ANCHOR_KEY);
        if (anchorTemplateId == null) {
            logger.warning("Sablona pro shulker anchor jeste nema potvrzene id v DB, zkus otevrit shulker znovu");
            return Optional.empty();
        }

        UUID uuid = UUID.randomUUID();
        itemTag.writeSingleUnit(shulkerItem, uuid, TemplateRegistry.SHULKER_ANCHOR_KEY);

        long now = System.currentTimeMillis();
        trackedUnitDao.enqueueUpsert(uuid.toString(), anchorTemplateId, "ITEM", "CONTAINER_ANCHOR", null, now, true, null, null);

        return Optional.of(uuid);
    }

    /**
     * Handles creative-mode duplication of an already-tracked physical item (e.g. middle-click
     * cloning a block/item you're holding): the clone starts out carrying the exact same PDC as
     * its source, which would mean the same UUID(s) physically exist twice - not allowed for
     * anything else, but creative duplication is explicitly allowed by design. Rather than
     * blocking it, this mints a fresh identity for every duplicated unit and records the lineage
     * via {@code duplicated_from_uuid} instead of treating it as a violation. Mutates the cloned
     * item's meta in place - same write-back contract as {@link #ensureTracked}.
     */
    public List<UUID> registerCreativeDuplicate(ItemStack clonedItem, List<UUID> originalUnits) {
        String templateKey = itemTag.readTemplateKey(clonedItem);
        if (templateKey == null) {
            return List.of();
        }
        Integer templateId = registry.idOf(templateKey);
        if (templateId == null) {
            logger.warning("Sablona '" + templateKey + "' jeste nema potvrzene id v DB, creative duplikace odlozena");
            return List.of();
        }

        long now = System.currentTimeMillis();
        List<UUID> freshUnits = new ArrayList<>(originalUnits.size());
        for (UUID original : originalUnits) {
            UUID fresh = UUID.randomUUID();
            freshUnits.add(fresh);
            trackedUnitDao.enqueueUpsert(fresh.toString(), templateId, "ITEM", "CREATIVE",
                    original.toString(), now, true, null, null);
            eventDao.enqueue(fresh.toString(), "GENESIS", now, null, null, null, null, null,
                    jsonField("duplicated_from", original.toString()));
        }

        itemTag.writeUnits(clonedItem, freshUnits, templateKey);
        return freshUnits;
    }

    /**
     * Upserts the unit's current location and appends a history event for the move.
     */
    public void recordLocation(UUID uuid, LocationContext ctx, String eventType, Player actor) {
        noteReappearance(uuid, ctx, actor);
        locationRecorder.record(uuid, ctx, eventType, actor);
    }

    /** Same as {@link #recordLocation} but for every unit in a (possibly merged) stack at once. */
    public void recordLocationForAll(List<UUID> uuids, LocationContext ctx, String eventType, Player actor) {
        for (UUID uuid : uuids) {
            noteReappearance(uuid, ctx, actor);
            locationRecorder.record(uuid, ctx, eventType, actor);
        }
    }

    public void markDestroyed(UUID uuid, String cause, Location location, Player actor) {
        long now = System.currentTimeMillis();
        trackedUnitDao.enqueueMarkDestroyed(uuid.toString(), now, cause);

        locationDao.enqueueUpsert(uuid.toString(), "DESTROYED", null, null,
                location != null ? location.getWorld().getName() : null,
                location != null ? location.getBlockX() : null,
                location != null ? location.getBlockY() : null,
                location != null ? location.getBlockZ() : null,
                null, null, null, null, now);

        String world = location != null ? location.getWorld().getName() : null;
        Integer x = location != null ? location.getBlockX() : null;
        Integer y = location != null ? location.getBlockY() : null;
        Integer z = location != null ? location.getBlockZ() : null;
        String actorUuid = actor != null ? actor.getUniqueId().toString() : null;
        eventDao.enqueue(uuid.toString(), "DESTROYED", now, world, x, y, z, actorUuid,
                jsonField("cause", cause), actor != null ? actor.getGameMode().name() : null,
                NearbyPlayers.capture(world, x, y, z, actorUuid));
    }

    /** Same as {@link #markDestroyed} but for every unit in a (possibly merged) stack at once. */
    public void markDestroyedForAll(List<UUID> uuids, String cause, Location location, Player actor) {
        for (UUID uuid : uuids) {
            markDestroyed(uuid, cause, location, actor);
        }
    }

    private static String jsonField(String field, String value) {
        return "{\"" + field + "\":\"" + escape(value) + "\"}";
    }

    private static String escape(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
