package eu.purrtech.detaillogger.db;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Single dedicated background thread that owns all writes to the SQLite writer connection -
 * this is SQLite's single-writer constraint made explicit rather than fought. Batches queued
 * tasks into one transaction every ~50ms (or every 200 tasks, whichever comes first), so writes
 * are flushed with bounded latency and RAM never accumulates unboundedly.
 */
final class DbWriterThread extends Thread {

    private static final int MAX_BATCH = 200;
    private static final long POLL_TIMEOUT_MS = 50;

    private final Connection connection;
    private final WriteQueue queue;
    private final Logger logger;
    private volatile boolean running = true;

    DbWriterThread(Connection connection, WriteQueue queue, Logger logger) {
        super("PurrTechDetailLogger-DbWriter");
        this.connection = connection;
        this.queue = queue;
        this.logger = logger;
        setDaemon(true);
    }

    @Override
    public void run() {
        while (running || queue.size() > 0) {
            try {
                DbTask first = queue.poll(POLL_TIMEOUT_MS);
                if (first == null) {
                    continue;
                }
                List<DbTask> batch = new ArrayList<>(MAX_BATCH);
                batch.add(first);
                batch.addAll(queue.drain(MAX_BATCH - 1));
                flush(batch);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void flush(List<DbTask> batch) {
        try {
            connection.setAutoCommit(false);
            try (PreparedStatement events = connection.prepareStatement("""
                            INSERT INTO events(unit_uuid, event_type, timestamp, world, x, y, z, player_uuid, detail, gamemode)
                            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                            """);
                 PreparedStatement units = connection.prepareStatement("""
                            INSERT INTO tracked_units(uuid, template_id, kind, origin, duplicated_from_uuid,
                                genesis_at, alive, destroyed_at, destroyed_cause)
                            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                            ON CONFLICT(uuid) DO UPDATE SET
                                alive=excluded.alive, destroyed_at=excluded.destroyed_at,
                                destroyed_cause=excluded.destroyed_cause
                            """);
                 PreparedStatement locations = connection.prepareStatement("""
                            INSERT INTO locations(unit_uuid, location_type, player_uuid, slot, world, x, y, z,
                                entity_uuid, container_type, parent_shulker_uuid, menu_name, updated_at)
                            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                            ON CONFLICT(unit_uuid) DO UPDATE SET
                                location_type=excluded.location_type, player_uuid=excluded.player_uuid,
                                slot=excluded.slot, world=excluded.world, x=excluded.x, y=excluded.y, z=excluded.z,
                                entity_uuid=excluded.entity_uuid, container_type=excluded.container_type,
                                parent_shulker_uuid=excluded.parent_shulker_uuid, menu_name=excluded.menu_name,
                                updated_at=excluded.updated_at
                            """);
                 PreparedStatement alerts = connection.prepareStatement("""
                            INSERT INTO dupe_alerts(template_id, unit_uuid, detected_at, expected_alive_count,
                                observed_count, world, x, y, z, player_uuid, severity, note)
                            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                            """);
                 PreparedStatement templates = connection.prepareStatement("""
                            INSERT INTO templates(key, material, custom_model_data, pdc_marker_key, pdc_marker_value,
                                name_pattern, lore_pattern, track_items, track_blocks, created_at)
                            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                            ON CONFLICT(key) DO UPDATE SET
                                material=excluded.material, custom_model_data=excluded.custom_model_data,
                                pdc_marker_key=excluded.pdc_marker_key, pdc_marker_value=excluded.pdc_marker_value,
                                name_pattern=excluded.name_pattern, lore_pattern=excluded.lore_pattern,
                                track_items=excluded.track_items, track_blocks=excluded.track_blocks
                            """);
                 PreparedStatement markDestroyed = connection.prepareStatement("""
                            UPDATE tracked_units SET alive = 0, destroyed_at = ?, destroyed_cause = ?
                            WHERE uuid = ?
                            """);
                 PreparedStatement updateKind = connection.prepareStatement("""
                            UPDATE tracked_units SET kind = ? WHERE uuid = ?
                            """);
                 PreparedStatement upsertPlayer = connection.prepareStatement("""
                            INSERT INTO players(uuid, current_name, first_joined_at, last_joined_at, online)
                            VALUES (?, ?, ?, ?, ?)
                            ON CONFLICT(uuid) DO UPDATE SET
                                current_name=excluded.current_name, last_joined_at=excluded.last_joined_at,
                                online=excluded.online
                            """);
                 PreparedStatement setPlayerOffline = connection.prepareStatement("""
                            UPDATE players SET online = 0, last_seen_at = ? WHERE uuid = ?
                            """);
                 PreparedStatement insertNameHistory = connection.prepareStatement("""
                            INSERT INTO player_name_history(player_uuid, name, changed_at) VALUES (?, ?, ?)
                            """);
                 PreparedStatement resetAllOffline = connection.prepareStatement("""
                            UPDATE players SET online = 0 WHERE online = 1
                            """)) {

                int eventCount = 0;
                int unitCount = 0;
                int locationCount = 0;
                int alertCount = 0;
                int templateCount = 0;
                int markDestroyedCount = 0;
                int updateKindCount = 0;
                int upsertPlayerCount = 0;
                int setPlayerOfflineCount = 0;
                int insertNameHistoryCount = 0;
                boolean resetAllOfflineRequested = false;

                for (DbTask task : batch) {
                    switch (task) {
                        case DbTask.InsertEventTask t -> {
                            bindEvent(events, t);
                            events.addBatch();
                            eventCount++;
                        }
                        case DbTask.UpsertTrackedUnitTask t -> {
                            bindUnit(units, t);
                            units.addBatch();
                            unitCount++;
                        }
                        case DbTask.UpsertLocationTask t -> {
                            bindLocation(locations, t);
                            locations.addBatch();
                            locationCount++;
                        }
                        case DbTask.InsertDupeAlertTask t -> {
                            bindAlert(alerts, t);
                            alerts.addBatch();
                            alertCount++;
                        }
                        case DbTask.UpsertTemplateTask t -> {
                            bindTemplate(templates, t);
                            templates.addBatch();
                            templateCount++;
                        }
                        case DbTask.MarkUnitDestroyedTask t -> {
                            bindMarkDestroyed(markDestroyed, t);
                            markDestroyed.addBatch();
                            markDestroyedCount++;
                        }
                        case DbTask.UpdateUnitKindTask t -> {
                            updateKind.setString(1, t.kind());
                            updateKind.setString(2, t.uuid());
                            updateKind.addBatch();
                            updateKindCount++;
                        }
                        case DbTask.UpsertPlayerTask t -> {
                            upsertPlayer.setString(1, t.uuid());
                            upsertPlayer.setString(2, t.name());
                            upsertPlayer.setLong(3, t.joinedAt());
                            upsertPlayer.setLong(4, t.joinedAt());
                            upsertPlayer.setBoolean(5, t.online());
                            upsertPlayer.addBatch();
                            upsertPlayerCount++;
                        }
                        case DbTask.SetPlayerOfflineTask t -> {
                            setPlayerOffline.setLong(1, t.lastSeenAt());
                            setPlayerOffline.setString(2, t.uuid());
                            setPlayerOffline.addBatch();
                            setPlayerOfflineCount++;
                        }
                        case DbTask.InsertNameHistoryTask t -> {
                            insertNameHistory.setString(1, t.playerUuid());
                            insertNameHistory.setString(2, t.name());
                            insertNameHistory.setLong(3, t.changedAt());
                            insertNameHistory.addBatch();
                            insertNameHistoryCount++;
                        }
                        case DbTask.ResetAllPlayersOfflineTask t -> resetAllOfflineRequested = true;
                    }
                }

                // Order matters: foreign keys are enforced immediately (not deferred), so anything
                // an FK points at must be written first - templates before units (template_id),
                // units before locations/events/alerts (unit_uuid), and the UPDATE-only statements
                // last so a genesis+immediate-update landing in the same batch sees the row.
                if (templateCount > 0) templates.executeBatch();
                if (unitCount > 0) units.executeBatch();
                if (locationCount > 0) locations.executeBatch();
                if (eventCount > 0) events.executeBatch();
                if (alertCount > 0) alerts.executeBatch();
                if (markDestroyedCount > 0) markDestroyed.executeBatch();
                if (updateKindCount > 0) updateKind.executeBatch();
                if (upsertPlayerCount > 0) upsertPlayer.executeBatch();
                if (setPlayerOfflineCount > 0) setPlayerOffline.executeBatch();
                if (insertNameHistoryCount > 0) insertNameHistory.executeBatch();
                if (resetAllOfflineRequested) resetAllOffline.executeUpdate();

                connection.commit();
            } catch (SQLException e) {
                connection.rollback();
                throw e;
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (SQLException e) {
            logger.log(Level.SEVERE, "Failed to flush " + batch.size() + " database write task(s)", e);
        }
    }

    private static void bindEvent(PreparedStatement ps, DbTask.InsertEventTask t) throws SQLException {
        ps.setString(1, t.unitUuid());
        ps.setString(2, t.eventType());
        ps.setLong(3, t.timestamp());
        ps.setString(4, t.world());
        setNullableInt(ps, 5, t.x());
        setNullableInt(ps, 6, t.y());
        setNullableInt(ps, 7, t.z());
        ps.setString(8, t.playerUuid());
        ps.setString(9, t.detailJson());
        ps.setString(10, t.gamemode());
    }

    private static void bindUnit(PreparedStatement ps, DbTask.UpsertTrackedUnitTask t) throws SQLException {
        ps.setString(1, t.uuid());
        ps.setInt(2, t.templateId());
        ps.setString(3, t.kind());
        ps.setString(4, t.origin());
        ps.setString(5, t.duplicatedFromUuid());
        ps.setLong(6, t.genesisAt());
        ps.setBoolean(7, t.alive());
        if (t.destroyedAt() != null) {
            ps.setLong(8, t.destroyedAt());
        } else {
            ps.setNull(8, Types.INTEGER);
        }
        ps.setString(9, t.destroyedCause());
    }

    private static void bindLocation(PreparedStatement ps, DbTask.UpsertLocationTask t) throws SQLException {
        ps.setString(1, t.unitUuid());
        ps.setString(2, t.locationType());
        ps.setString(3, t.playerUuid());
        setNullableInt(ps, 4, t.slot());
        ps.setString(5, t.world());
        setNullableInt(ps, 6, t.x());
        setNullableInt(ps, 7, t.y());
        setNullableInt(ps, 8, t.z());
        ps.setString(9, t.entityUuid());
        ps.setString(10, t.containerType());
        ps.setString(11, t.parentShulkerUuid());
        ps.setString(12, t.menuName());
        ps.setLong(13, t.updatedAt());
    }

    private static void bindAlert(PreparedStatement ps, DbTask.InsertDupeAlertTask t) throws SQLException {
        setNullableInt(ps, 1, t.templateId());
        ps.setString(2, t.unitUuid());
        ps.setLong(3, t.detectedAt());
        setNullableInt(ps, 4, t.expectedAliveCount());
        setNullableInt(ps, 5, t.observedCount());
        ps.setString(6, t.world());
        setNullableInt(ps, 7, t.x());
        setNullableInt(ps, 8, t.y());
        setNullableInt(ps, 9, t.z());
        ps.setString(10, t.playerUuid());
        ps.setString(11, t.severity());
        ps.setString(12, t.note());
    }

    private static void bindTemplate(PreparedStatement ps, DbTask.UpsertTemplateTask t) throws SQLException {
        ps.setString(1, t.key());
        ps.setString(2, t.material());
        setNullableInt(ps, 3, t.customModelData());
        ps.setString(4, t.pdcMarkerKey());
        ps.setString(5, t.pdcMarkerValue());
        ps.setString(6, t.namePattern());
        ps.setString(7, t.lorePattern());
        ps.setBoolean(8, t.trackItems());
        ps.setBoolean(9, t.trackBlocks());
        ps.setLong(10, t.createdAt());
    }

    private static void bindMarkDestroyed(PreparedStatement ps, DbTask.MarkUnitDestroyedTask t) throws SQLException {
        ps.setLong(1, t.destroyedAt());
        ps.setString(2, t.destroyedCause());
        ps.setString(3, t.uuid());
    }

    private static void setNullableInt(PreparedStatement ps, int index, Integer value) throws SQLException {
        if (value != null) {
            ps.setInt(index, value);
        } else {
            ps.setNull(index, Types.INTEGER);
        }
    }

    void shutdown() {
        running = false;
        try {
            join(5000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
