package eu.purrtech.detaillogger.db;

/**
 * Immutable unit of work for {@link DbWriterThread}. Listeners on the main thread build one of
 * these and hand it to {@link WriteQueue#offer(DbTask)} - no I/O ever happens on the main thread.
 */
public sealed interface DbTask {

    record InsertEventTask(String unitUuid, String eventType, long timestamp, String world,
                            Integer x, Integer y, Integer z, String playerUuid,
                            String detailJson, String gamemode) implements DbTask {
    }

    record UpsertTrackedUnitTask(String uuid, int templateId, String kind, String origin,
                                  String duplicatedFromUuid, long genesisAt, boolean alive,
                                  Long destroyedAt, String destroyedCause) implements DbTask {
    }

    record UpsertLocationTask(String unitUuid, String locationType, String playerUuid, Integer slot,
                               String world, Integer x, Integer y, Integer z, String entityUuid,
                               String containerType, String parentShulkerUuid, String menuName,
                               long updatedAt) implements DbTask {
    }

    record InsertDupeAlertTask(Integer templateId, String unitUuid, long detectedAt,
                                Integer expectedAliveCount, Integer observedCount, String world,
                                Integer x, Integer y, Integer z, String playerUuid, String severity,
                                String note) implements DbTask {
    }

    record UpsertTemplateTask(String key, String material, Integer customModelData,
                               String pdcMarkerKey, String pdcMarkerValue,
                               String namePattern, String lorePattern,
                               boolean trackItems, boolean trackBlocks, long createdAt) implements DbTask {
    }

    /**
     * Marks a unit destroyed without needing to know its original template/kind/origin - unlike
     * {@link UpsertTrackedUnitTask}'s conflict-update path, which requires re-supplying those.
     */
    record MarkUnitDestroyedTask(String uuid, long destroyedAt, String destroyedCause) implements DbTask {
    }

    /**
     * Transitions a unit between ITEM and BLOCK kind (e.g. an item placed as a block, or a block
     * broken back into an item), without disturbing its other fields.
     */
    record UpdateUnitKindTask(String uuid, String kind) implements DbTask {
    }

    /**
     * Upsert on join: first_joined_at/last_seen_at are intentionally left out of the
     * ON CONFLICT SET clause so they survive across joins (see the writer's bound SQL).
     */
    record UpsertPlayerTask(String uuid, String name, long joinedAt, boolean online) implements DbTask {
    }

    record SetPlayerOfflineTask(String uuid, long lastSeenAt) implements DbTask {
    }

    record InsertNameHistoryTask(String playerUuid, String name, long changedAt) implements DbTask {
    }

    /** Startup safety net against "online" rows left behind by an unclean shutdown. */
    record ResetAllPlayersOfflineTask() implements DbTask {
    }
}
