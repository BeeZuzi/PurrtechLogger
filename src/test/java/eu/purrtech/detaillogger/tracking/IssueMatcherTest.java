package eu.purrtech.detaillogger.tracking;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IssueMatcherTest {

    private static final IssueMatcher.Key KEY = new IssueMatcher.Key(UUID.randomUUID(), "key");

    @Test
    void reportBeforeTheItemSettlesTheGain() {
        IssueMatcher matcher = new IssueMatcher();
        assertEquals(0, matcher.addCredit(KEY, 3, "crate", 0, 10_000).amount());
        assertEquals(3, matcher.addGain(KEY, 3, "no cause", 100, 5_000).amount());
        assertTrue(matcher.expire(1_000_000).isEmpty()); // nothing left unexplained
    }

    @Test
    void reportAfterTheItemSettlesTheWaitingGain() {
        IssueMatcher matcher = new IssueMatcher();
        assertEquals(0, matcher.addGain(KEY, 3, "no cause", 0, 5_000).amount());
        IssueMatcher.Matched matched = matcher.addCredit(KEY, 3, "crate", 2_000, 10_000);
        assertEquals(3, matched.amount());
        assertEquals("crate", matched.text());
        assertTrue(matcher.expire(1_000_000).isEmpty());
    }

    @Test
    void gainWithoutAnyReportIsUnexplainedOnceGraceRunsOut() {
        IssueMatcher matcher = new IssueMatcher();
        matcher.addGain(KEY, 5, "no cause", 0, 5_000);
        assertTrue(matcher.expire(4_999).isEmpty()); // still inside the grace period
        var unexplained = matcher.expire(5_001);
        assertEquals(1, unexplained.size());
        assertEquals(5, unexplained.get(0).amount());
        assertTrue(matcher.expire(9_999_999).isEmpty()); // reported only once
    }

    @Test
    void partialReportLeavesTheRestUnexplained() {
        IssueMatcher matcher = new IssueMatcher();
        matcher.addGain(KEY, 5, "no cause", 0, 5_000);
        assertEquals(2, matcher.addCredit(KEY, 2, "crate", 1_000, 10_000).amount());
        var unexplained = matcher.expire(6_000);
        assertEquals(3, unexplained.get(0).amount()); // the player got 5 but only 2 were reported
    }

    @Test
    void reportedMoreThanReceivedStaysAsCreditForALaterGain() {
        IssueMatcher matcher = new IssueMatcher();
        matcher.addCredit(KEY, 4, "crate", 0, 10_000);
        assertEquals(1, matcher.addGain(KEY, 1, "no cause", 1_000, 5_000).amount());
        assertEquals(3, matcher.addGain(KEY, 3, "no cause", 2_000, 5_000).amount());
    }

    @Test
    void anExpiredCreditDoesNotCoverALaterGain() {
        IssueMatcher matcher = new IssueMatcher();
        matcher.addCredit(KEY, 3, "crate", 0, 1_000);
        assertEquals(0, matcher.addGain(KEY, 3, "no cause", 5_000, 5_000).amount());
    }
}
