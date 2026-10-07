package eu.purrtech.detaillogger.db;

import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Real SQLite (in memory) with the real schema: one bad row must not cost the rest of its batch. */
class DbWriterThreadTest {

    private static DbTask unit(String uuid) {
        return new DbTask.UpsertTrackedUnitTask(uuid, 0, "ITEM", "TEST", null, 1, true, null, null);
    }

    private static DbTask event(String uuid) {
        return new DbTask.InsertEventTask(uuid, "SEEN", 1, null, null, null, null, null, null, null, null);
    }

    private static int count(Connection c, String table) throws Exception {
        try (Statement s = c.createStatement(); ResultSet r = s.executeQuery("select count(*) from " + table)) {
            return r.getInt(1);
        }
    }

    @Test
    void badForeignKeyRowIsSkippedAndTheRestOfTheBatchIsWritten() throws Exception {
        Logger logger = Logger.getAnonymousLogger();
        try (Connection c = DriverManager.getConnection("jdbc:sqlite::memory:")) {
            try (Statement s = c.createStatement()) {
                s.execute("PRAGMA foreign_keys=ON");
            }
            new SchemaMigrator(c, logger).migrate();

            WriteQueue queue = new WriteQueue(1000, logger);
            queue.configure(1000, java.util.Map.of("SEEN", WritePriority.HIGHEST));
            queue.offer(unit("good-1"));
            queue.offer(event("good-1"));
            queue.offer(event("never-inserted")); // FK violation in the middle of the batch
            queue.offer(unit("good-2"));
            queue.offer(event("good-2"));

            DbWriterThread writer = new DbWriterThread(c, queue, logger); // not started yet: one batch
            writer.start();
            writer.shutdown();

            assertEquals(2, count(c, "tracked_units"));
            assertEquals(2, count(c, "events")); // the bad one skipped, both good ones kept
            assertEquals(4, queue.stats().writtenTotal()); // 5 offered - 1 skipped
        }
    }
}
