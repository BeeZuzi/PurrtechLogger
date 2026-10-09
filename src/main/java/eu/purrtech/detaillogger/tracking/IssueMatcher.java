package eu.purrtech.detaillogger.tracking;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Pairs two things that happen in no fixed order: a player's count of a ledger item going UP with no
 * ordinary cause (a "gain"), and a third-party plugin telling us it handed those items out
 * ({@code /purrlog issue}, a "credit"). A crate may run its give before or after the report, so:
 * <ul>
 *   <li>a gain first uses up credits that are already there, and what is left waits ({@code grace}) for
 *       a late credit;</li>
 *   <li>a credit first settles gains that are already waiting, and what is left waits ({@code ttl}) for
 *       a gain that has not arrived yet.</li>
 * </ul>
 * Whatever is still waiting as a gain after its grace period is unexplained - that is what raises an
 * alert. Pure logic with no Bukkit types, so it is unit-tested.
 */
final class IssueMatcher {

    record Key(UUID player, String template) {
    }

    /** How much of an incoming gain/credit was settled by the other side, and the reasons involved. */
    record Matched(int amount, String text) {
    }

    record Unexplained(Key key, int amount, String note) {
    }

    private static final class Entry {
        int remaining;
        final long deadline;
        final String text;

        Entry(int remaining, long deadline, String text) {
            this.remaining = remaining;
            this.deadline = deadline;
            this.text = text;
        }
    }

    private final Map<Key, Deque<Entry>> credits = new HashMap<>();
    private final Map<Key, Deque<Entry>> gains = new HashMap<>();

    /** An unexplained gain of {@code amount} appeared. */
    Matched addGain(Key key, int amount, String note, long now, long graceMillis) {
        Matched matched = consume(credits.get(key), amount, now);
        int left = amount - matched.amount();
        if (left > 0) {
            gains.computeIfAbsent(key, k -> new ArrayDeque<>()).add(new Entry(left, now + graceMillis, note));
        }
        return matched;
    }

    /** A plugin reported that it issued {@code amount}. */
    Matched addCredit(Key key, int amount, String reason, long now, long ttlMillis) {
        Matched matched = consume(gains.get(key), amount, now);
        int left = amount - matched.amount();
        if (left > 0) {
            credits.computeIfAbsent(key, k -> new ArrayDeque<>()).add(new Entry(left, now + ttlMillis, reason));
        }
        return new Matched(matched.amount(), reason);
    }

    /** Gains whose grace period ran out without a report. Also forgets expired credits. */
    List<Unexplained> expire(long now) {
        List<Unexplained> unexplained = new ArrayList<>();
        for (Iterator<Map.Entry<Key, Deque<Entry>>> it = gains.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<Key, Deque<Entry>> pair = it.next();
            for (Iterator<Entry> entries = pair.getValue().iterator(); entries.hasNext(); ) {
                Entry entry = entries.next();
                if (entry.deadline < now) {
                    unexplained.add(new Unexplained(pair.getKey(), entry.remaining, entry.text));
                    entries.remove();
                }
            }
            if (pair.getValue().isEmpty()) {
                it.remove();
            }
        }
        credits.values().forEach(deque -> deque.removeIf(entry -> entry.deadline < now));
        credits.values().removeIf(Deque::isEmpty);
        return unexplained;
    }

    /** Takes up to {@code amount} from the oldest still-valid entries. */
    private static Matched consume(Deque<Entry> deque, int amount, long now) {
        if (deque == null) {
            return new Matched(0, "");
        }
        int used = 0;
        StringBuilder text = new StringBuilder();
        for (Iterator<Entry> it = deque.iterator(); it.hasNext() && used < amount; ) {
            Entry entry = it.next();
            if (entry.deadline < now) {
                continue; // too late to count; expire() reports it (gains) or drops it (credits)
            }
            int take = Math.min(entry.remaining, amount - used);
            entry.remaining -= take;
            used += take;
            if (text.length() > 0) {
                text.append(", ");
            }
            text.append(entry.text);
            if (entry.remaining == 0) {
                it.remove();
            }
        }
        return new Matched(used, text.toString());
    }
}
