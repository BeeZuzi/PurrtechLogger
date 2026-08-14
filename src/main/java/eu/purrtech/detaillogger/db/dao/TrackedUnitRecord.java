package eu.purrtech.detaillogger.db.dao;

public record TrackedUnitRecord(
        String uuid,
        int templateId,
        String kind,
        String origin,
        String duplicatedFromUuid,
        long genesisAt,
        boolean alive,
        Long destroyedAt,
        String destroyedCause
) {
}
