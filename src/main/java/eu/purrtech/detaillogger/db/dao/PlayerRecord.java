package eu.purrtech.detaillogger.db.dao;

public record PlayerRecord(
        String uuid,
        String currentName,
        long firstJoinedAt,
        long lastJoinedAt,
        Long lastSeenAt,
        boolean online
) {
}
