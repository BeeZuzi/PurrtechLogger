package eu.purrtech.detaillogger.db;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

/**
 * FIFO queue between event listeners (main thread) and {@link DbWriterThread}. One queue, so every
 * task is written in the order it was enqueued - a unit's creation always precedes the
 * locations/events that reference it.
 * <p>
 * Load shedding is split by what a drop costs. <b>Bulk</b> tasks (events, locations, dupe alerts -
 * high volume, one or two per tracked item per action) are capped at {@code bulkCapacity} pending
 * and dropped beyond that, so a write storm can't grow memory without limit. <b>Everything else</b>
 * (templates, tracked units, destroy/kind updates, players) is never dropped: other rows point at
 * them by foreign key, and losing one made every later write for that unit fail
 * ("FOREIGN KEY constraint failed"). These are small and far rarer, so leaving them unbounded is
 * safe.
 * <p>
 * Drops are logged as one summary line per interval instead of one line per task, which used to
 * flood the console during exactly the storm that caused them.
 */
public final class WriteQueue {

    private static final long DROP_LOG_INTERVAL_MS = 5_000;

    private final LinkedBlockingQueue<DbTask> queue = new LinkedBlockingQueue<>();
    private final AtomicInteger bulkPending = new AtomicInteger();
    private final AtomicLong droppedSinceLog = new AtomicLong();
    private volatile long lastDropLogAt;
    private final int bulkCapacity;
    private final Logger logger;

    WriteQueue(int bulkCapacity, Logger logger) {
        this.bulkCapacity = bulkCapacity;
        this.logger = logger;
    }

    private static boolean isBulk(DbTask task) {
        return task instanceof DbTask.InsertEventTask
                || task instanceof DbTask.UpsertLocationTask
                || task instanceof DbTask.InsertDupeAlertTask;
    }

    public void offer(DbTask task) {
        if (isBulk(task)) {
            if (bulkPending.incrementAndGet() > bulkCapacity) {
                bulkPending.decrementAndGet();
                droppedSinceLog.incrementAndGet();
                logDrops();
                return;
            }
        }
        queue.add(task);
    }

    private void logDrops() {
        long now = System.currentTimeMillis();
        if (now - lastDropLogAt < DROP_LOG_INTERVAL_MS) {
            return;
        }
        lastDropLogAt = now;
        long dropped = droppedSinceLog.getAndSet(0);
        if (dropped > 0) {
            logger.warning("Fronta zapisu je plna (" + bulkCapacity + " udalosti/poloh ceka) - zahozeno "
                    + dropped + " zapisu udalosti/poloh za poslednich par vterin. Zapisy jednotek a sablon se nezahazuji.");
        }
    }

    DbTask poll(long timeoutMs) throws InterruptedException {
        DbTask task = queue.poll(timeoutMs, TimeUnit.MILLISECONDS);
        if (task != null && isBulk(task)) {
            bulkPending.decrementAndGet();
        }
        return task;
    }

    List<DbTask> drain(int max) {
        List<DbTask> batch = new ArrayList<>(max);
        queue.drainTo(batch, max);
        for (DbTask task : batch) {
            if (isBulk(task)) {
                bulkPending.decrementAndGet();
            }
        }
        return batch;
    }

    int size() {
        return queue.size();
    }
}
