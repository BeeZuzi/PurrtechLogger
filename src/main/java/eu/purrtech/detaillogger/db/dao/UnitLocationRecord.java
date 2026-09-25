package eu.purrtech.detaillogger.db.dao;

/** A tracked unit's last known location row - see {@link LocationDao#findByUnit}. */
public record UnitLocationRecord(
        String locationType,
        String playerUuid,
        Integer slot,
        String world,
        Integer x,
        Integer y,
        Integer z,
        String containerType,
        String menuName
) {
}
