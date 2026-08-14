package eu.purrtech.detaillogger.db.dao;

import eu.purrtech.detaillogger.db.Database;
import eu.purrtech.detaillogger.db.DbTask;
import eu.purrtech.detaillogger.db.MainThreadCheck;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class TrackedUnitDao {

    private final Database database;

    public TrackedUnitDao(Database database) {
        this.database = database;
    }

    public void enqueueUpsert(String uuid, int templateId, String kind, String origin,
                               String duplicatedFromUuid, long genesisAt, boolean alive,
                               Long destroyedAt, String destroyedCause) {
        database.writeQueue().offer(new DbTask.UpsertTrackedUnitTask(
                uuid, templateId, kind, origin, duplicatedFromUuid, genesisAt, alive, destroyedAt, destroyedCause));
    }

    public void enqueueMarkDestroyed(String uuid, long destroyedAt, String destroyedCause) {
        database.writeQueue().offer(new DbTask.MarkUnitDestroyedTask(uuid, destroyedAt, destroyedCause));
    }

    public void enqueueUpdateKind(String uuid, String kind) {
        database.writeQueue().offer(new DbTask.UpdateUnitKindTask(uuid, kind));
    }

    /**
     * Blocking read - must be called off the main thread.
     */
    public TrackedUnitRecord findByUuid(String uuid) throws SQLException {
        MainThreadCheck.assertAsync();
        Connection connection = borrow();
        try (PreparedStatement ps = connection.prepareStatement("""
                SELECT uuid, template_id, kind, origin, duplicated_from_uuid, genesis_at, alive,
                       destroyed_at, destroyed_cause
                FROM tracked_units WHERE uuid = ?
                """)) {
            ps.setString(1, uuid);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                long destroyedAtValue = rs.getLong("destroyed_at");
                Long destroyedAt = rs.wasNull() ? null : destroyedAtValue;

                return new TrackedUnitRecord(
                        rs.getString("uuid"),
                        rs.getInt("template_id"),
                        rs.getString("kind"),
                        rs.getString("origin"),
                        rs.getString("duplicated_from_uuid"),
                        rs.getLong("genesis_at"),
                        rs.getBoolean("alive"),
                        destroyedAt,
                        rs.getString("destroyed_cause")
                );
            }
        } finally {
            database.readPool().release(connection);
        }
    }

    /**
     * Blocking read - must be called off the main thread. Maps only the uuids that actually exist
     * in the DB; a uuid absent from the result is unknown to the DB entirely.
     */
    public Map<String, Boolean> findAliveStatus(List<String> uuids) throws SQLException {
        MainThreadCheck.assertAsync();
        if (uuids.isEmpty()) {
            return Map.of();
        }
        Connection connection = borrow();
        try {
            Map<String, Boolean> result = new HashMap<>();
            String placeholders = String.join(",", uuids.stream().map(u -> "?").toList());
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT uuid, alive FROM tracked_units WHERE uuid IN (" + placeholders + ")")) {
                for (int i = 0; i < uuids.size(); i++) {
                    ps.setString(i + 1, uuids.get(i));
                }
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        result.put(rs.getString("uuid"), rs.getBoolean("alive"));
                    }
                }
            }
            return result;
        } finally {
            database.readPool().release(connection);
        }
    }

    private Connection borrow() throws SQLException {
        try {
            return database.readPool().borrow();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SQLException("Interrupted while waiting for a read connection", e);
        }
    }
}
