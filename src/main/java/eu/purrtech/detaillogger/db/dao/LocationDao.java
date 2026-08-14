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

public final class LocationDao {

    private final Database database;

    public LocationDao(Database database) {
        this.database = database;
    }

    public void enqueueUpsert(String unitUuid, String locationType, String playerUuid, Integer slot,
                               String world, Integer x, Integer y, Integer z, String entityUuid,
                               String containerType, String parentShulkerUuid, String menuName,
                               long updatedAt) {
        database.writeQueue().offer(new DbTask.UpsertLocationTask(
                unitUuid, locationType, playerUuid, slot, world, x, y, z, entityUuid,
                containerType, parentShulkerUuid, menuName, updatedAt));
    }

    /**
     * Blocking read - must be called off the main thread. Used to (re)populate
     * {@code BlockIdentityIndex} for a single chunk on load, so plain-block identity never has to
     * live in memory beyond currently loaded chunks.
     */
    public List<BlockLocationRecord> findBlockLocationsInChunk(String world, int chunkX, int chunkZ) throws SQLException {
        MainThreadCheck.assertAsync();
        int minX = chunkX << 4;
        int maxX = minX + 15;
        int minZ = chunkZ << 4;
        int maxZ = minZ + 15;

        Connection connection = borrow();
        try (PreparedStatement ps = connection.prepareStatement("""
                SELECT l.unit_uuid, t.key AS template_key, l.x, l.y, l.z
                FROM locations l
                JOIN tracked_units u ON u.uuid = l.unit_uuid
                JOIN templates t ON t.id = u.template_id
                WHERE l.location_type = 'PLACED_BLOCK' AND l.world = ?
                  AND l.x BETWEEN ? AND ? AND l.z BETWEEN ? AND ?
                """)) {
            ps.setString(1, world);
            ps.setInt(2, minX);
            ps.setInt(3, maxX);
            ps.setInt(4, minZ);
            ps.setInt(5, maxZ);
            try (ResultSet rs = ps.executeQuery()) {
                List<BlockLocationRecord> results = new ArrayList<>();
                while (rs.next()) {
                    results.add(new BlockLocationRecord(
                            rs.getString("unit_uuid"),
                            rs.getString("template_key"),
                            rs.getInt("x"),
                            rs.getInt("y"),
                            rs.getInt("z")
                    ));
                }
                return results;
            }
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
