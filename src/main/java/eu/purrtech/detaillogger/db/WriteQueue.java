package eu.purrtech.detaillogger.db;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * Bounded queue between event listeners (main thread) and {@link DbWriterThread}. Bounded so a
 * write storm (e.g. an explosion destroying dozens of tracked blocks in one tick) can't grow
 * pending-write RAM without limit - if the queue is full, the task is dropped and logged rather
 * than blocking the main thread or buffering indefinitely.
 */
public final class WriteQueue {

    private final ArrayBlockingQueue<DbTask> queue;
    private final Logger logger;

    WriteQueue(int capacity, Logger logger) {
        this.queue = new ArrayBlockingQueue<>(capacity);
        this.logger = logger;
    }

    public void offer(DbTask task) {
        if (!queue.offer(task)) {
            logger.warning("Database write queue is full (" + queue.size()
                    + " pending) - dropping " + task.getClass().getSimpleName());
        }
    }

    DbTask poll(long timeoutMs) throws InterruptedException {
        return queue.poll(timeoutMs, TimeUnit.MILLISECONDS);
    }

    List<DbTask> drain(int max) {
        List<DbTask> batch = new ArrayList<>(max);
        queue.drainTo(batch, max);
        return batch;
    }

    int size() {
        return queue.size();
    }
}
