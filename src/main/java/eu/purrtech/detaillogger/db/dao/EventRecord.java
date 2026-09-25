package eu.purrtech.detaillogger.db.dao;

public record EventRecord(
        long id,
        String unitUuid,
        String eventType,
        long timestamp,
        String world,
        Integer x,
        Integer y,
        Integer z,
        String playerUuid,
        String detail,
        String gamemode,
        /** Comma-separated names of other online players who were within
         * {@link eu.purrtech.detaillogger.tracking.NearbyPlayers#DEFAULT_RADIUS_BLOCKS} blocks
         * when this event was recorded, or null if unknown/nobody else was around. */
        String nearbyPlayers
) {
}
