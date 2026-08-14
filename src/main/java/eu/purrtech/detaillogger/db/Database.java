package eu.purrtech.detaillogger.db;

import org.bukkit.plugin.Plugin;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.logging.Logger;

/**
 * Owns the SQLite writer connection, the async write pipeline and the read connection pool for
 * the plugin's whole lifetime. This is the single entry point the rest of the plugin talks to.
 */
public final class Database {

    private final Plugin plugin;
    private final Logger logger;
    private final File dbFile;

    private Connection writerConnection;
    private WriteQueue writeQueue;
    private DbWriterThread writerThread;
    private ReadConnectionPool readPool;

    public Database(Plugin plugin) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
        this.dbFile = new File(plugin.getDataFolder(), "detaillogger.db");
    }

    public void open() {
        try {
            if (!plugin.getDataFolder().exists() && !plugin.getDataFolder().mkdirs()) {
                throw new IllegalStateException("Failed to create plugin data folder: " + plugin.getDataFolder());
            }

            String url = "jdbc:sqlite:" + dbFile.getAbsolutePath();
            writerConnection = DriverManager.getConnection(url);
            configure(writerConnection);
            new SchemaMigrator(writerConnection, logger).migrate();

            writeQueue = new WriteQueue(10_000, logger);
            writerThread = new DbWriterThread(writerConnection, writeQueue, logger);
            writerThread.start();

            readPool = new ReadConnectionPool(url, 4, logger);
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to open database at " + dbFile, e);
        }
    }

    private static void configure(Connection connection) throws SQLException {
        try (Statement st = connection.createStatement()) {
            st.execute("PRAGMA journal_mode=WAL");
            st.execute("PRAGMA synchronous=NORMAL");
            st.execute("PRAGMA foreign_keys=ON");
            st.execute("PRAGMA busy_timeout=5000");
        }
    }

    public WriteQueue writeQueue() {
        return writeQueue;
    }

    public ReadConnectionPool readPool() {
        return readPool;
    }

    public void close() {
        if (writerThread != null) {
            writerThread.shutdown();
        }
        if (readPool != null) {
            readPool.close();
        }
        try {
            if (writerConnection != null && !writerConnection.isClosed()) {
                writerConnection.close();
            }
        } catch (SQLException e) {
            logger.warning("Failed to close writer connection: " + e.getMessage());
        }
    }
}
