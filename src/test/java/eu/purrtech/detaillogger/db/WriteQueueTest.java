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
