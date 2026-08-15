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

public final class PlayerDao {

    private final Database database;

    public PlayerDao(Database database) {
        this.database = database;
    }

    public void enqueueUpsertOnJoin(String uuid, String name, long joinedAt) {
        database.writeQueue().offer(new DbTask.UpsertPlayerTask(uuid, name, joinedAt, true));
    }

    public void enqueueSetOffline(String uuid, long lastSeenAt) {
        database.writeQueue().offer(new DbTask.SetPlayerOfflineTask(uuid, lastSeenAt));
    }

    public void enqueueNameChange(String uuid, String name, long changedAt) {
        database.writeQueue().offer(new DbTask.InsertNameHistoryTask(uuid, name, changedAt));
    }

    /** Startup safety net - see {@link DbTask.ResetAllPlayersOfflineTask}. */
    public void enqueueResetAllOffline() {
        database.writeQueue().offer(new DbTask.ResetAllPlayersOfflineTask());
    }

    /**
     * Blocking read - must be called off the main thread.
     */
    public PlayerRecord findByUuid(String uuid) throws SQLException {
        MainThreadCheck.assertAsync();
        Connection connection = borrow();
        try (PreparedStatement ps = connection.prepareStatement("""
                SELECT uuid, current_name, first_joined_at, last_joined_at, last_seen_at, online
                FROM players WHERE uuid = ?
                """)) {
            ps.setString(1, uuid);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                return readPlayer(rs);
            }
        } finally {
            database.readPool().release(connection);
        }
    }

    /**
     * Blocking read - must be called off the main thread. Every player who has ever joined,
     * online first, then most recently seen - never a player who never joined (there's simply no
     * row for one).
     */
    public List<PlayerRecord> findAll() throws SQLException {
        MainThreadCheck.assertAsync();
        Connection connection = borrow();
        try (PreparedStatement ps = connection.prepareStatement("""
                SELECT uuid, current_name, first_joined_at, last_joined_at, last_seen_at, online
                FROM players ORDER BY online DESC, last_seen_at DESC, last_joined_at DESC
                """)) {
            try (ResultSet rs = ps.executeQuery()) {
                List<PlayerRecord> results = new ArrayList<>();
                while (rs.next()) {
                    results.add(readPlayer(rs));
                }
                return results;
            }
        } finally {
            database.readPool().release(connection);
        }
    }

    /**
     * Blocking read - must be called off the main thread. Matches a nickname (partial,
     * case-insensitive) or an exact UUID.
     */
    public List<PlayerRecord> search(String query) throws SQLException {
        MainThreadCheck.assertAsync();
        Connection connection = borrow();
        try (PreparedStatement ps = connection.prepareStatement("""
                SELECT uuid, current_name, first_joined_at, last_joined_at, last_seen_at, online
                FROM players WHERE uuid = ? OR current_name LIKE ? ESCAPE '\\'
                ORDER BY online DESC, last_seen_at DESC, last_joined_at DESC
                """)) {
            ps.setString(1, query);
            ps.setString(2, "%" + likeEscape(query) + "%");
            try (ResultSet rs = ps.executeQuery()) {
                List<PlayerRecord> results = new ArrayList<>();
                while (rs.next()) {
                    results.add(readPlayer(rs));
                }
                return results;
            }
        } finally {
            database.readPool().release(connection);
        }
    }

    /**
     * Blocking read - must be called off the main thread. Oldest first, so a player's nicknames
     * read as a timeline.
     */
    public List<PlayerNameHistoryRecord> findNameHistory(String playerUuid) throws SQLException {
        MainThreadCheck.assertAsync();
        Connection connection = borrow();
        try (PreparedStatement ps = connection.prepareStatement("""
                SELECT name, changed_at FROM player_name_history WHERE player_uuid = ? ORDER BY changed_at
                """)) {
            ps.setString(1, playerUuid);
            try (ResultSet rs = ps.executeQuery()) {
                List<PlayerNameHistoryRecord> results = new ArrayList<>();
                while (rs.next()) {
                    results.add(new PlayerNameHistoryRecord(rs.getString("name"), rs.getLong("changed_at")));
                }
                return results;
            }
        } finally {
            database.readPool().release(connection);
        }
    }

    private static PlayerRecord readPlayer(ResultSet rs) throws SQLException {
        long lastSeenAtValue = rs.getLong("last_seen_at");
        Long lastSeenAt = rs.wasNull() ? null : lastSeenAtValue;
        return new PlayerRecord(
                rs.getString("uuid"),
                rs.getString("current_name"),
                rs.getLong("first_joined_at"),
                rs.getLong("last_joined_at"),
                lastSeenAt,
                rs.getBoolean("online")
        );
    }

    private static String likeEscape(String s) {
        return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
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
