package eu.purrtech.detaillogger.tracking;

import eu.purrtech.detaillogger.db.dao.EventDao;
import eu.purrtech.detaillogger.db.dao.LocationDao;
import eu.purrtech.detaillogger.db.dao.TrackedUnitDao;
import eu.purrtech.detaillogger.template.TemplateDefinition;
import eu.purrtech.detaillogger.template.TemplateMatcher;
import eu.purrtech.detaillogger.template.TemplateRegistry;
import eu.purrtech.detaillogger.tracking.pdc.TrackedBlockTag;
import eu.purrtech.detaillogger.tracking.pdc.TrackedItemTag;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.Optional;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Block lifecycle operations: genesis, the item&lt;-&gt;block identity bridge, and destruction.
 * Mirrors {@link ItemTrackingService} but for the structurally different block-identity problem -
 * TileState blocks (chest/barrel/furnace/shulker) carry their own PDC, plain blocks (stone,
 * ore...) don't support PDC at all so their identity lives purely in {@link BlockIdentityIndex}
 * (mirrored from the DB, bounded to loaded chunks).
 */
public final class BlockTrackingService {

    private final TrackedBlockTag blockTag;
    private final TrackedItemTag itemTag;
    private final TemplateMatcher matcher;
    private final TemplateRegistry registry;
    private final TrackedUnitDao trackedUnitDao;
    private final EventDao eventDao;
    private final BlockIdentityIndex index;
    private final LocationRecorder locationRecorder;
    private final Logger logger;

    public BlockTrackingService(TrackedBlockTag blockTag, TrackedItemTag itemTag, TemplateMatcher matcher,
                                 TemplateRegistry registry, TrackedUnitDao trackedUnitDao, LocationDao locationDao,
                                 EventDao eventDao, BlockIdentityIndex index, Logger logger) {
        this.blockTag = blockTag;
        this.itemTag = itemTag;
        this.matcher = matcher;
        this.registry = registry;
        this.trackedUnitDao = trackedUnitDao;
        this.eventDao = eventDao;
        this.index = index;
        this.locationRecorder = new LocationRecorder(locationDao, eventDao);
        this.logger = logger;
    }

    public record TrackedBlock(UUID uuid, String templateKey) {
    }

    /**
     * Reads a block's tracked identity regardless of whether it's a TileState (PDC, authoritative
     * locally) or a plain block ({@link BlockIdentityIndex}, mirrored from the DB). Null if
     * untracked.
     */
    public TrackedBlock readTracked(Block block) {
        if (blockTag.supports(block)) {
            UUID uuid = blockTag.readUuid(block);
            return uuid == null ? null : new TrackedBlock(uuid, blockTag.readTemplateKey(block));
        }
        BlockIdentityIndex.Entry entry = index.get(block.getWorld().getName(), block.getX(), block.getY(), block.getZ());
        if (entry == null) {
            return null;
        }
        try {
            return new TrackedBlock(UUID.fromString(entry.uuid()), entry.templateKey());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * Called from BlockPlaceEvent. If the placed item was already tracked, transfers its identity
     * onto the block (item -&gt; block). Otherwise, if the resulting block itself matches a
     * track-blocks template on its own, genesis happens directly as a BLOCK-kind unit.
     */
    public void onBlockPlaced(Block block, ItemStack placedItem, Player player) {
        UUID itemUuid = itemTag.readUuid(placedItem);
        if (itemUuid != null) {
            String templateKey = itemTag.readTemplateKey(placedItem);
            tagBlock(block, itemUuid, templateKey);
            trackedUnitDao.enqueueUpdateKind(itemUuid.toString(), "BLOCK");
            locationRecorder.record(itemUuid, LocationContext.placedBlock(block.getLocation(), block.getType().name()),
                    "PLACED", player);
            return;
        }

        Optional<TemplateDefinition> match = matcher.match(block);
        if (match.isEmpty()) {
            return;
        }
        TemplateDefinition def = match.get();
        Integer templateId = registry.idOf(def.key());
        if (templateId == null) {
            logger.warning("Sablona '" + def.key() + "' jeste nema potvrzene id v DB, genesis bloku odlozena");
            return;
        }

        UUID uuid = UUID.randomUUID();
        tagBlock(block, uuid, def.key());

        long now = System.currentTimeMillis();
        trackedUnitDao.enqueueUpsert(uuid.toString(), templateId, "BLOCK", "PLACED", null, now, true, null, null);
        locationRecorder.record(uuid, LocationContext.placedBlock(block.getLocation(), block.getType().name()), "GENESIS", player);
    }

    /**
     * Called from BlockBreakEvent for an already-confirmed-tracked block. Returns a fresh
     * ItemStack carrying the same UUID (identity transferred back to item form) for the caller to
     * spawn/give instead of the natural drop. Doesn't attempt exact drop-table fidelity (fortune,
     * silk touch producing a different item, etc.) - the replacement is simply one of the block's
     * own material.
     */
    public ItemStack onBlockBroken(Block block, UUID uuid, String templateKey, Player player) {
        untagBlock(block);
        trackedUnitDao.enqueueUpdateKind(uuid.toString(), "ITEM");

        ItemStack drop = new ItemStack(block.getType());
        itemTag.writeSingleUnit(drop, uuid, templateKey);

        locationRecorder.record(uuid, LocationContext.ground(block.getLocation()), "BROKEN", player);
        return drop;
    }

    public void markDestroyed(UUID uuid, String cause, Block block) {
        untagBlock(block);
        long now = System.currentTimeMillis();
        trackedUnitDao.enqueueMarkDestroyed(uuid.toString(), now, cause);
        eventDao.enqueue(uuid.toString(), "DESTROYED", now, block.getWorld().getName(),
                block.getX(), block.getY(), block.getZ(), null, jsonField("cause", cause));
    }

    /** Relocates a plain (non-TileState) tracked block, e.g. pushed by a piston. */
    public void moveBlock(UUID uuid, String templateKey, Location from, Location to) {
        index.move(from.getWorld().getName(), from.getBlockX(), from.getBlockY(), from.getBlockZ(),
                to.getBlockX(), to.getBlockY(), to.getBlockZ());
        locationRecorder.record(uuid, LocationContext.placedBlock(to, templateKey), "MOVED", null);
    }

    /**
     * Removes local tagging (PDC or index) without marking the unit destroyed - used when a
     * tracked block transitions into a {@code FallingBlock} entity. The DB row stays
     * {@code alive=true}; its location goes briefly stale until {@link #relandBlock} reconnects
     * it once the entity lands.
     */
    public void untrackForFall(Block block) {
        untagBlock(block);
    }

    /**
     * Called (a tick later - see the falling-block listener) once a landed block has actually
     * taken on its new material, to re-tag it and restore its location.
     */
    public void relandBlock(Block block, UUID uuid, String templateKey) {
        tagBlock(block, uuid, templateKey);
        locationRecorder.record(uuid, LocationContext.placedBlock(block.getLocation(), block.getType().name()), "MOVED", null);
    }

    private void tagBlock(Block block, UUID uuid, String templateKey) {
        if (blockTag.supports(block)) {
            blockTag.write(block, uuid, templateKey);
        } else {
            index.put(block.getWorld().getName(), block.getX(), block.getY(), block.getZ(), uuid.toString(), templateKey);
        }
    }

    private void untagBlock(Block block) {
        if (blockTag.supports(block)) {
            blockTag.strip(block);
        } else {
            index.remove(block.getWorld().getName(), block.getX(), block.getY(), block.getZ());
        }
    }

    private static String jsonField(String field, String value) {
        return "{\"" + field + "\":\"" + (value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"")) + "\"}";
    }
}
