package eu.purrtech.detaillogger.db.dao;

import eu.purrtech.detaillogger.db.Database;
import eu.purrtech.detaillogger.db.DbTask;

/** Book-keeping for {@code mode: ledger} items - see V5__ledger.sql. */
public final class LedgerDao {

    private final Database database;

    public LedgerDao(Database database) {
        this.database = database;
    }

    public void enqueue(long at, String playerUuid, String templateKey, int delta, String cause,
                        Integer totalAfter, String detail) {
        database.writeQueue().offer(new DbTask.InsertLedgerTask(at, playerUuid, templateKey, delta, cause,
                totalAfter, detail));
    }
}
