package eu.purrtech.detaillogger.db.dao;

import eu.purrtech.detaillogger.db.Database;
import eu.purrtech.detaillogger.db.DbTask;
import eu.purrtech.detaillogger.db.MainThreadCheck;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

public final class EventDao {

    private final Database database;

    public EventDao(Database database) {
        this.database = database;
    }

    public void enqueue(String unitUuid, String eventType, long timestamp, String world,
                         Integer x, Integer y, Integer z, String playerUuid, String detailJson) {
        enqueue(unitUuid, eventType, timestamp, world, x, y, z, playerUuid, detailJson, null, null);
    }

    public void enqueue(String unitUuid, String eventType, long timestamp, String world,
                         Integer x, Integer y, Integer z, String playerUuid, String detailJson,
                         String gamemode) {
        enqueue(unitUuid, eventType, timestamp, world, x, y, z, playerUuid, detailJson, gamemode, null);
    }

    /**
     * @param nearbyPlayers comma-separated names of other online players who were near this
     *                       event's location when it happened - see
     *                       {@link eu.purrtech.detaillogger.tracking.NearbyPlayers}. Null if
     *                       unknown (no location) or nobody else was around.
     */
    public void enqueue(String unitUuid, String eventType, long timestamp, String world,
                         Integer x, Integer y, Integer z, String playerUuid, String detailJson,
                         String gamemode, String nearbyPlayers) {
        database.writeQueue().offer(new DbTask.InsertEventTask(
                unitUuid, eventType, timestamp, world, x, y, z, playerUuid, detailJson, gamemode, nearbyPlayers));
    }

    /**
     * Blocking read - must be called off the main thread. Excludes {@code MOVED} (a tracked
     * item's own within-inventory slot reshuffle) - by far the noisiest, least meaningful event
     * type for a human reading history, and pure clutter compared to genesis/placement/
     * destruction/dupe events. Still written to the DB by every writer unchanged (see
     * {@link eu.purrtech.detaillogger.db.DbWriterThread}) - only reads here filter it out, so it
     * stays available for anti-dupe forensics via a direct SQL query if ever needed.
     */
    public List<EventRecord> findByUnit(String unitUuid) throws SQLException {
        MainThreadCheck.assertAsync();
        Connection connection = borrow();
        try (PreparedStatement ps = connection.prepareStatement("""
                SELECT id, unit_uuid, event_type, timestamp, world, x, y, z, player_uuid, detail, gamemode, nearby_players
                FROM events WHERE unit_uuid = ? AND event_type != 'MOVED' ORDER BY timestamp
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
     * {@code MOVED} is excluded before the limit is applied (not after) - see
     * {@link #findByUnit} - otherwise an active player's noisy slot-shuffling would crowd out
     * genuinely meaningful events before they ever reach the cap.
     */
    public List<EventRecord> findByPlayer(String playerUuid, int limit) throws SQLException {
        MainThreadCheck.assertAsync();
        Connection connection = borrow();
        try (PreparedStatement ps = connection.prepareStatement("""
                SELECT id, unit_uuid, event_type, timestamp, world, x, y, z, player_uuid, detail, gamemode, nearby_players
                FROM events WHERE player_uuid = ? AND event_type != 'MOVED' ORDER BY timestamp DESC LIMIT ?
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

    /**
     * Blocking read - must be called off the main thread. Newest first, capped at {@code limit}.
     * Used by the admin GUI's "all events" browser: {@code eventTypes} narrows to those types
     * when non-empty (no restriction otherwise), and either bound is skipped when {@code null}.
     * Unlike {@link #findByUnit}/{@link #findByPlayer}, {@code MOVED} is not excluded here since
     * this is meant to show literally everything the category filter allows through.
     */
    public List<EventRecord> findFiltered(Collection<String> eventTypes, Long fromMillisInclusive,
                                           Long toMillisInclusive, int limit) throws SQLException {
        MainThreadCheck.assertAsync();
        StringBuilder sql = new StringBuilder("""
                SELECT id, unit_uuid, event_type, timestamp, world, x, y, z, player_uuid, detail, gamemode, nearby_players
                FROM events WHERE 1=1
                """);
        List<Object> params = new ArrayList<>();
        if (eventTypes != null && !eventTypes.isEmpty()) {
            String placeholders = eventTypes.stream().map(t -> "?").collect(Collectors.joining(","));
            sql.append(" AND event_type IN (").append(placeholders).append(')');
            params.addAll(eventTypes);
        }
        if (fromMillisInclusive != null) {
            sql.append(" AND timestamp >= ?");
            params.add(fromMillisInclusive);
        }
        if (toMillisInclusive != null) {
            sql.append(" AND timestamp <= ?");
            params.add(toMillisInclusive);
        }
        sql.append(" ORDER BY timestamp DESC LIMIT ?");
        params.add(limit);

        Connection connection = borrow();
        try (PreparedStatement ps = connection.prepareStatement(sql.toString())) {
            for (int i = 0; i < params.size(); i++) {
                ps.setObject(i + 1, params.get(i));
            }
            try (ResultSet rs = ps.executeQuery()) {
                return readEvents(rs);
            }
        } finally {
            database.readPool().release(connection);
        }
    }

    /**
     * Blocking read - must be called off the main thread. Looks a single event up by its own
     * primary key - per "Ukládej to pod ID kdy to půjde kdykoliv najít" (store it under an ID so
     * it can always be found), used by the admin GUI's event detail page so it always shows
     * freshly-persisted data instead of only whatever was in memory from the list query.
     */
    public Optional<EventRecord> findById(long id) throws SQLException {
        MainThreadCheck.assertAsync();
        Connection connection = borrow();
        try (PreparedStatement ps = connection.prepareStatement("""
                SELECT id, unit_uuid, event_type, timestamp, world, x, y, z, player_uuid, detail, gamemode, nearby_players
                FROM events WHERE id = ?
                """)) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                List<EventRecord> results = readEvents(rs);
                return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
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
                    rs.getString("gamemode"),
                    rs.getString("nearby_players")
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
