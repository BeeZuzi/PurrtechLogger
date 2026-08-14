package eu.purrtech.detaillogger.db.dao;

import eu.purrtech.detaillogger.db.Database;
import eu.purrtech.detaillogger.db.DbTask;
import eu.purrtech.detaillogger.db.MainThreadCheck;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

public final class DupeAlertDao {

    private final Database database;

    public DupeAlertDao(Database database) {
        this.database = database;
    }

    public void enqueueAlert(Integer templateId, String unitUuid, long detectedAt,
                              Integer expectedAliveCount, Integer observedCount, String world,
                              Integer x, Integer y, Integer z, String playerUuid, String severity,
                              String note) {
        database.writeQueue().offer(new DbTask.InsertDupeAlertTask(
                templateId, unitUuid, detectedAt, expectedAliveCount, observedCount, world,
                x, y, z, playerUuid, severity, note));
    }

    /**
     * Blocking read - must be called off the main thread.
     */
    public List<DupeAlertRecord> findUnresolved(int limit) throws SQLException {
        MainThreadCheck.assertAsync();
        Connection connection = borrow();
        try (PreparedStatement ps = connection.prepareStatement("""
                SELECT id, template_id, unit_uuid, detected_at, expected_alive_count, observed_count,
                       world, x, y, z, player_uuid, severity, note
                FROM dupe_alerts WHERE resolved = 0 ORDER BY detected_at DESC LIMIT ?
                """)) {
            ps.setInt(1, limit);
            try (ResultSet rs = ps.executeQuery()) {
                List<DupeAlertRecord> results = new ArrayList<>();
                while (rs.next()) {
                    results.add(new DupeAlertRecord(
                            rs.getLong("id"),
                            nullableInt(rs, "template_id"),
                            rs.getString("unit_uuid"),
                            rs.getLong("detected_at"),
                            nullableInt(rs, "expected_alive_count"),
                            nullableInt(rs, "observed_count"),
                            rs.getString("world"),
                            nullableInt(rs, "x"),
                            nullableInt(rs, "y"),
                            nullableInt(rs, "z"),
                            rs.getString("player_uuid"),
                            rs.getString("severity"),
                            rs.getString("note")
                    ));
                }
                return results;
            }
        } finally {
            database.readPool().release(connection);
        }
    }

    private static Integer nullableInt(ResultSet rs, String column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
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
