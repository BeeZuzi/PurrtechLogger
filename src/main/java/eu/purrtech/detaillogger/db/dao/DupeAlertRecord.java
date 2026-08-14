package eu.purrtech.detaillogger.db.dao;

public record DupeAlertRecord(
        long id,
        Integer templateId,
        String unitUuid,
        long detectedAt,
        Integer expectedAliveCount,
        Integer observedCount,
        String world,
        Integer x,
        Integer y,
        Integer z,
        String playerUuid,
        String severity,
        String note
) {
}
