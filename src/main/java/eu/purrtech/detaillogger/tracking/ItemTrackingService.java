package eu.purrtech.detaillogger.tracking;

import eu.purrtech.detaillogger.db.dao.EventDao;
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
import java.util.Optional;
import java.util.UUID;
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
    private final LocationRecorder locationRecorder;
    private final StackReconciler stackReconciler;
    private final Logger logger;

    public ItemTrackingService(TrackedItemTag itemTag, TemplateMatcher matcher, TemplateRegistry registry,
                                TrackedUnitDao trackedUnitDao, LocationDao locationDao, EventDao eventDao,
                                Logger logger) {
        this.itemTag = itemTag;
        this.matcher = matcher;
        this.registry = registry;
        this.trackedUnitDao = trackedUnitDao;
        this.locationDao = locationDao;
        this.eventDao = eventDao;
        this.locationRecorder = new LocationRecorder(locationDao, eventDao);
        this.stackReconciler = new StackReconciler(itemTag);
        this.logger = logger;
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
        stackReconciler.reconcile(affectedStacks);
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
        Integer templateId = registry.idOf(def.key());
        if (templateId == null) {
            logger.warning("Sablona '" + def.key() + "' jeste nema potvrzene id v DB, genesis odlozena do dalsi observace");
            return Optional.empty();
        }

        UUID uuid = UUID.randomUUID();
        itemTag.writeSingleUnit(item, uuid, def.key());

        long now = System.currentTimeMillis();
        trackedUnitDao.enqueueUpsert(uuid.toString(), templateId, "ITEM", origin, null, now, true, null, null);
        eventDao.enqueue(uuid.toString(), "GENESIS", now, null, null, null, null, null, jsonField("origin", origin));

        return Optional.of(uuid);
    }

    /**
     * Stack-aware version of {@link #ensureTracked}: an already-tracked stack (scalar or merged
     * list) yields every unit it currently holds, an untracked template-matching item genesis'd
     * as a fresh single unit yields a singleton list, and a non-matching untracked item yields an
     * empty list. This is the entry point every location/observation listener should use instead
     * of {@link #ensureTracked} once merged stacks are possible.
     */
    public List<UUID> ensureTrackedAll(ItemStack item, String origin) {
        List<UUID> existing = itemTag.readUnits(item);
        if (!existing.isEmpty()) {
            return existing;
        }
        return ensureTracked(item, origin).map(List::of).orElse(List.of());
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
        locationRecorder.record(uuid, ctx, eventType, actor);
    }

    /** Same as {@link #recordLocation} but for every unit in a (possibly merged) stack at once. */
    public void recordLocationForAll(List<UUID> uuids, LocationContext ctx, String eventType, Player actor) {
        for (UUID uuid : uuids) {
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

        eventDao.enqueue(uuid.toString(), "DESTROYED", now,
                location != null ? location.getWorld().getName() : null,
                location != null ? location.getBlockX() : null,
                location != null ? location.getBlockY() : null,
                location != null ? location.getBlockZ() : null,
                actor != null ? actor.getUniqueId().toString() : null,
                jsonField("cause", cause),
                actor != null ? actor.getGameMode().name() : null);
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
