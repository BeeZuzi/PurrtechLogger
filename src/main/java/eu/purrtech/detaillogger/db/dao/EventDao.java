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
        enqueue(unitUuid, eventType, timestamp, world, x, y, z, playerUuid, detailJson, null);
    }

    public void enqueue(String unitUuid, String eventType, long timestamp, String world,
                         Integer x, Integer y, Integer z, String playerUuid, String detailJson,
                         String gamemode) {
        database.writeQueue().offer(new DbTask.InsertEventTask(
                unitUuid, eventType, timestamp, world, x, y, z, playerUuid, detailJson, gamemode));
    }

    /**
     * Blocking read - must be called off the main thread.
     */
    public List<EventRecord> findByUnit(String unitUuid) throws SQLException {
        MainThreadCheck.assertAsync();
        Connection connection = borrow();
        try (PreparedStatement ps = connection.prepareStatement("""
                SELECT id, unit_uuid, event_type, timestamp, world, x, y, z, player_uuid, detail, gamemode
                FROM events WHERE unit_uuid = ? ORDER BY timestamp
                """)) {
            ps.setString(1, unitUuid);
            try (ResultSet rs = ps.executeQuery()) {
                return readEvents(rs);
            }
        } finally {
            database.readPool().release(connection);
        }
    }

    /**
     * Blocking read - must be called off the main thread. Newest first, capped at {@code limit}
     * so an active player's admin-GUI activity page never has to pull an unbounded history.
     */
    public List<EventRecord> findByPlayer(String playerUuid, int limit) throws SQLException {
        MainThreadCheck.assertAsync();
        Connection connection = borrow();
        try (PreparedStatement ps = connection.prepareStatement("""
                SELECT id, unit_uuid, event_type, timestamp, world, x, y, z, player_uuid, detail, gamemode
                FROM events WHERE player_uuid = ? ORDER BY timestamp DESC LIMIT ?
                """)) {
            ps.setString(1, playerUuid);
            ps.setInt(2, limit);
            try (ResultSet rs = ps.executeQuery()) {
                return readEvents(rs);
            }
        } finally {
            database.readPool().release(connection);
        }
    }

    private static List<EventRecord> readEvents(ResultSet rs) throws SQLException {
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
                    rs.getString("detail"),
                    rs.getString("gamemode")
            ));
        }
        return results;
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
