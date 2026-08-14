package eu.purrtech.detaillogger.db;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.logging.Logger;

/**
 * Small fixed pool of read-only connections (safe for concurrent readers under WAL mode), used
 * from async tasks for history/report queries. Never touched from the main thread.
 */
public final class ReadConnectionPool {

    private final ArrayBlockingQueue<Connection> pool;
    private final Logger logger;

    ReadConnectionPool(String jdbcUrl, int size, Logger logger) throws SQLException {
        this.logger = logger;
        this.pool = new ArrayBlockingQueue<>(size);
        for (int i = 0; i < size; i++) {
            Connection connection = DriverManager.getConnection(jdbcUrl);
            try (Statement st = connection.createStatement()) {
                st.execute("PRAGMA query_only=ON");
                st.execute("PRAGMA busy_timeout=5000");
            }
            pool.add(connection);
        }
    }

    public Connection borrow() throws InterruptedException {
        return pool.take();
    }

    public void release(Connection connection) {
        pool.offer(connection);
    }

    void close() {
        for (Connection connection : pool) {
            try {
                connection.close();
            } catch (SQLException e) {
                logger.warning("Failed to close read connection: " + e.getMessage());
            }
        }
        pool.clear();
    }
}
