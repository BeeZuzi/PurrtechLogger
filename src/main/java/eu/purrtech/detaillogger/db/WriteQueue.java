package eu.purrtech.detaillogger.db;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
    // Counters for /purrlog debug stats - since server start.
    private final AtomicLong droppedTotal = new AtomicLong();
    private final AtomicLong writtenTotal = new AtomicLong();
    private final AtomicLong writeNanosTotal = new AtomicLong();
    private volatile int peakPending;
    private volatile int lastBatchTasks;
    private volatile long lastBatchMillis;
    private final long startedAt = System.currentTimeMillis();

    /** Snapshot for {@code /purrlog debug stats}. */
    public record Stats(int pending, int bulkPending, int bulkCapacity, int peakPending, long droppedTotal,
                        long writtenTotal, long writeMillisTotal, int lastBatchTasks, long lastBatchMillis,
                        long uptimeMillis) {
    }

    public Stats stats() {
        return new Stats(queue.size(), bulkPending.get(), bulkCapacity, peakPending, droppedTotal.get(),
                writtenTotal.get(), writeNanosTotal.get() / 1_000_000, lastBatchTasks, lastBatchMillis,
                System.currentTimeMillis() - startedAt);
    }

    /** Called by the writer after each committed batch. */
    void recordFlush(int tasks, long nanos) {
        writtenTotal.addAndGet(tasks);
        writeNanosTotal.addAndGet(nanos);
        lastBatchTasks = tasks;
        lastBatchMillis = nanos / 1_000_000;
    }
    private volatile long lastDropLogAt;
    private volatile int bulkCapacity;
    /** Event type -> priority (config.yml {@code event-priority}); a type not listed is MEDIUM. */
    private volatile Map<String, WritePriority> eventPriorities = Map.of();
    private final Logger logger;

    WriteQueue(int bulkCapacity, Logger logger) {
        this.bulkCapacity = bulkCapacity;
        this.logger = logger;
    }

    /** Applies config.yml; safe to call again on /purrlog reload. */
    public void configure(int bulkCapacity, Map<String, WritePriority> eventPriorities) {
        this.bulkCapacity = bulkCapacity;
        this.eventPriorities = Map.copyOf(eventPriorities);
    }

    private static boolean isBulk(DbTask task) {
        return task instanceof DbTask.InsertEventTask
                || task instanceof DbTask.UpsertLocationTask
                || task instanceof DbTask.InsertDupeAlertTask
                || task instanceof DbTask.InsertLedgerTask;
    }

    /**
     * Priority a bulk task is queued at. Events use their type's configured priority; a unit's
     * location row is current state, not history, so it ranks HIGH regardless of which event
     * moved it (an OFF event type still keeps its location up to date); dupe alerts are HIGHEST.
     */
    private WritePriority priorityOf(DbTask task) {
        if (task instanceof DbTask.InsertEventTask event) {
            return eventPriorities.getOrDefault(event.eventType(), WritePriority.MEDIUM);
        }
        return task instanceof DbTask.InsertDupeAlertTask ? WritePriority.HIGHEST : WritePriority.HIGH;
    }

    public void offer(DbTask task) {
        if (isBulk(task)) {
            WritePriority priority = priorityOf(task);
            if (priority == WritePriority.OFF) {
                return; // owner turned this event type off - not a load drop, nothing to log
            }
            if (bulkPending.incrementAndGet() > priority.limit(bulkCapacity)) {
                bulkPending.decrementAndGet();
                droppedSinceLog.incrementAndGet();
                droppedTotal.incrementAndGet();
                logDrops();
                return;
            }
        }
        queue.add(task);
        int size = queue.size();
        if (size > peakPending) {
            peakPending = size; // racy by design: a stat, an off-by-a-few is fine
        }
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
