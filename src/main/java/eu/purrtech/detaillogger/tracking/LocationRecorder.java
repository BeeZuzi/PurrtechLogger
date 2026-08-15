package eu.purrtech.detaillogger.tracking;

import eu.purrtech.detaillogger.db.dao.EventDao;
import eu.purrtech.detaillogger.db.dao.LocationDao;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.UUID;

/**
 * Shared "upsert current location + append a history event" logic used by both
 * {@link ItemTrackingService} and {@link BlockTrackingService}.
 */
final class LocationRecorder {

    private final LocationDao locationDao;
    private final EventDao eventDao;

    LocationRecorder(LocationDao locationDao, EventDao eventDao) {
        this.locationDao = locationDao;
        this.eventDao = eventDao;
    }

    /**
     * The event's world/x/y/z falls back to the actor's own position when the location itself
     * has no coordinates (player inventory, ender chest, entity container) - purely so the
     * timeline still carries "where in the world did this happen" context.
     */
    void record(UUID uuid, LocationContext ctx, String eventType, Player actor) {
        long now = System.currentTimeMillis();
        locationDao.enqueueUpsert(uuid.toString(), ctx.locationType(), ctx.playerUuid(), ctx.slot(),
                ctx.world(), ctx.x(), ctx.y(), ctx.z(), ctx.entityUuid(), ctx.containerType(),
                ctx.parentShulkerUuid(), ctx.menuName(), now);

        String eventWorld = ctx.world();
        Integer eventX = ctx.x();
        Integer eventY = ctx.y();
        Integer eventZ = ctx.z();
        if (eventWorld == null && actor != null) {
            Location loc = actor.getLocation();
            eventWorld = loc.getWorld().getName();
            eventX = loc.getBlockX();
            eventY = loc.getBlockY();
            eventZ = loc.getBlockZ();
        }

        eventDao.enqueue(uuid.toString(), eventType, now, eventWorld, eventX, eventY, eventZ,
                actor != null ? actor.getUniqueId().toString() : null, null,
                actor != null ? actor.getGameMode().name() : null);
    }
}
