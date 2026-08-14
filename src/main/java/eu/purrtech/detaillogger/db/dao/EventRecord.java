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
        String detail
) {
}
