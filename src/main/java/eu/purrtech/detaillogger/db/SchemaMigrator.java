package eu.purrtech.detaillogger.db;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.logging.Logger;

/**
 * Applies numbered SQL migration files from {@code /db/migrations} in order, tracked via the
 * {@code schema_version} table so restarts only apply what's new.
 */
final class SchemaMigrator {

    private static final List<String> MIGRATIONS = List.of("V1__init.sql", "V2__players.sql");

    private final Connection connection;
    private final Logger logger;

    SchemaMigrator(Connection connection, Logger logger) {
        this.connection = connection;
        this.logger = logger;
    }

    void migrate() throws SQLException {
        try (Statement st = connection.createStatement()) {
            st.execute("""
                    CREATE TABLE IF NOT EXISTS schema_version (
                        version INTEGER PRIMARY KEY,
                        applied_at INTEGER NOT NULL
                    )
                    """);
        }

        int current = currentVersion();
        for (String migration : MIGRATIONS) {
            int version = parseVersion(migration);
            if (version > current) {
                apply(migration, version);
            }
        }
    }

    private int currentVersion() throws SQLException {
        try (Statement st = connection.createStatement();
             ResultSet rs = st.executeQuery("SELECT COALESCE(MAX(version), 0) FROM schema_version")) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    private void apply(String migration, int version) throws SQLException {
        String sql = readResource(migration);
        connection.setAutoCommit(false);
        try (Statement st = connection.createStatement()) {
            for (String statement : sql.split(";")) {
                String trimmed = statement.trim();
                if (!trimmed.isEmpty()) {
                    st.execute(trimmed);
                }
            }
            try (var ps = connection.prepareStatement(
                    "INSERT INTO schema_version(version, applied_at) VALUES (?, ?)")) {
                ps.setInt(1, version);
                ps.setLong(2, System.currentTimeMillis());
                ps.executeUpdate();
            }
            connection.commit();
            logger.info("Applied database migration " + migration);
        } catch (SQLException e) {
            connection.rollback();
            throw e;
        } finally {
            connection.setAutoCommit(true);
        }
    }

    private static int parseVersion(String migration) {
        String withoutV = migration.substring(1);
        return Integer.parseInt(withoutV.substring(0, withoutV.indexOf('_')));
    }

    private static String readResource(String name) {
        String path = "/db/migrations/" + name;
        try (InputStream in = SchemaMigrator.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("Missing migration resource: " + path);
            }
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line).append('\n');
                }
                return sb.toString();
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read migration resource: " + path, e);
        }
    }
}
