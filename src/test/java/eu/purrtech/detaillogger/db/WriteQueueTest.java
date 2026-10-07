package eu.purrtech.detaillogger.db;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WriteQueueTest {

    private static DbTask event() {
        return new DbTask.InsertEventTask("u", "SEEN", 0, null, null, null, null, null, null, null, null);
    }

    private static DbTask unit(int i) {
        return new DbTask.UpsertTrackedUnitTask("u" + i, 1, "ITEM", "TEST", null, 0, true, null, null);
    }

    @Test
    void bulkIsCappedButUnitsAreNeverDropped() {
        WriteQueue queue = new WriteQueue(3, Logger.getAnonymousLogger());
        queue.configure(3, java.util.Map.of("SEEN", WritePriority.HIGHEST)); // MEDIUM would stop at 80%
        for (int i = 0; i < 10; i++) {
            queue.offer(event());
        }
        for (int i = 0; i < 10; i++) {
            queue.offer(unit(i)); // must all survive even though the bulk cap is hit
        }
        assertEquals(3 + 10, queue.size());

        List<DbTask> drained = queue.drain(100); // draining frees the bulk budget again
        assertEquals(13, drained.size());
        queue.offer(event());
        assertEquals(1, queue.size());
    }

    private static DbTask event(String type) {
        return new DbTask.InsertEventTask("u", type, 0, null, null, null, null, null, null, null, null);
    }

    @Test
    void lowPriorityIsShedFirstAndOffIsNeverStored() {
        WriteQueue queue = new WriteQueue(100, Logger.getAnonymousLogger());
        queue.configure(100, java.util.Map.of("MOVED", WritePriority.LOWEST, "GENESIS", WritePriority.HIGHEST,
                "SEEN", WritePriority.OFF));
        for (int i = 0; i < 100; i++) {
            queue.offer(event("MOVED"));
        }
        assertEquals(50, queue.size()); // LOWEST stops at 50% full
        for (int i = 0; i < 100; i++) {
            queue.offer(event("GENESIS"));
        }
        assertEquals(100, queue.size()); // HIGHEST fills the rest, then is dropped too
        queue.drain(100);
        queue.offer(event("SEEN"));
        assertEquals(0, queue.size()); // OFF: not stored
    }

    @Test
    void statsCountPeakDroppedAndWritten() {
        WriteQueue queue = new WriteQueue(10, Logger.getAnonymousLogger());
        queue.configure(10, java.util.Map.of("SEEN", WritePriority.HIGHEST));
        for (int i = 0; i < 15; i++) {
            queue.offer(event()); // 10 accepted, 5 dropped
        }
        queue.drain(100);
        queue.recordFlush(10, 20_000_000L); // 10 tasks in 20 ms
        WriteQueue.Stats stats = queue.stats();
        assertEquals(10, stats.peakPending());
        assertEquals(5, stats.droppedTotal());
        assertEquals(10, stats.writtenTotal());
        assertEquals(20, stats.lastBatchMillis());
        assertEquals(0, stats.pending());
    }

    @Test
    void keepsEnqueueOrder() throws InterruptedException {
        WriteQueue queue = new WriteQueue(10, Logger.getAnonymousLogger());
        DbTask u = unit(1);
        DbTask e = event();
        queue.offer(u);
        queue.offer(e);
        assertEquals(List.of(u, e), List.of(queue.poll(10), queue.poll(10)));
    }
}
