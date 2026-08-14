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

public final class TemplateDao {

    private final Database database;

    public TemplateDao(Database database) {
        this.database = database;
    }

    public void enqueueUpsert(String key, String material, Integer customModelData,
                               String pdcMarkerKey, String pdcMarkerValue,
                               String namePattern, String lorePattern,
                               boolean trackItems, boolean trackBlocks, long createdAt) {
        database.writeQueue().offer(new DbTask.UpsertTemplateTask(
                key, material, customModelData, pdcMarkerKey, pdcMarkerValue,
                namePattern, lorePattern, trackItems, trackBlocks, createdAt));
    }

    /**
     * Blocking read - must be called off the main thread. Returns only the keys that currently
     * exist in the DB; callers poll until the set they expect is complete (writes go through the
     * async queue, so a freshly-enqueued upsert isn't visible immediately).
     */
    public Map<String, Integer> findIdsByKeys(List<String> keys) throws SQLException {
        MainThreadCheck.assertAsync();
        if (keys.isEmpty()) {
            return Map.of();
        }
        Connection connection = borrow();
        try {
            Map<String, Integer> result = new HashMap<>();
            String placeholders = String.join(",", keys.stream().map(k -> "?").toList());
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT key, id FROM templates WHERE key IN (" + placeholders + ")")) {
                for (int i = 0; i < keys.size(); i++) {
                    ps.setString(i + 1, keys.get(i));
                }
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        result.put(rs.getString("key"), rs.getInt("id"));
                    }
                }
            }
            return result;
        } finally {
            database.readPool().release(connection);
        }
    }

    /**
     * Blocking read - must be called off the main thread.
     */
    public String findMaterialById(int templateId) throws SQLException {
        MainThreadCheck.assertAsync();
        Connection connection = borrow();
        try (PreparedStatement ps = connection.prepareStatement("SELECT material FROM templates WHERE id = ?")) {
            ps.setInt(1, templateId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString("material") : null;
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
