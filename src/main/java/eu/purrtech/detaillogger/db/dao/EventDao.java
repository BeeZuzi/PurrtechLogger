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

public final class EventDao {

    private final Database database;

    public EventDao(Database database) {
        this.database = database;
    }

    public void enqueue(String unitUuid, String eventType, long timestamp, String world,
                         Integer x, Integer y, Integer z, String playerUuid, String detailJson) {
        database.writeQueue().offer(new DbTask.InsertEventTask(
                unitUuid, eventType, timestamp, world, x, y, z, playerUuid, detailJson));
    }

    /**
     * Blocking read - must be called off the main thread.
     */
    public List<EventRecord> findByUnit(String unitUuid) throws SQLException {
        MainThreadCheck.assertAsync();
        Connection connection = borrow();
        try (PreparedStatement ps = connection.prepareStatement("""
                SELECT id, unit_uuid, event_type, timestamp, world, x, y, z, player_uuid, detail
                FROM events WHERE unit_uuid = ? ORDER BY timestamp
                """)) {
            ps.setString(1, unitUuid);
            try (ResultSet rs = ps.executeQuery()) {
                List<EventRecord> results = new ArrayList<>();
                while (rs.next()) {
                    results.add(new EventRecord(
                            rs.getLong("id"),
                            rs.getString("unit_uuid"),
                            rs.getString("event_type"),
                            rs.getLong("timestamp"),
                            rs.getString("world"),
                            nullableInt(rs, "x"),
                            nullableInt(rs, "y"),
                            nullableInt(rs, "z"),
                            rs.getString("player_uuid"),
                            rs.getString("detail")
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
