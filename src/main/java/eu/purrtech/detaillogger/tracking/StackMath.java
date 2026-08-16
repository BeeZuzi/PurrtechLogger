package eu.purrtech.detaillogger.tracking;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Pure logic for repairing a set of tracked stacks affected by the same inventory action, with no
 * Bukkit dependency (so it's directly unit-testable). Each tracked unit carries its own distinct
 * PDC, so vanilla Minecraft splitting a stack (e.g. right-click take-half) naively clones that PDC
 * onto both resulting stacks - the same UUID then appears to exist in two places at once, which is
 * exactly what an anti-dupe check would flag. This detects that and re-slices the UUIDs across the
 * affected stacks according to their actual resulting amounts, so every unit ends up in exactly
 * one place.
 */
public final class StackMath {

    private StackMath() {
    }

    /**
     * @param observedPerSlot the units currently tagged on each affected stack, in the same order
     *                        as {@code targetAmounts}
     * @param targetAmounts   each stack's actual current {@code amount} (the ground truth -
     *                        Bukkit gets amounts right even when it clones NBT wrongly)
     * @return the corrected per-slot unit lists, or the input unchanged if no duplicate was found
     *         (the common case - most inventory actions don't need any repair)
     */
    public static List<List<String>> reslice(List<List<String>> observedPerSlot, List<Integer> targetAmounts) {
        if (observedPerSlot.size() != targetAmounts.size()) {
            throw new IllegalArgumentException("observedPerSlot and targetAmounts must be the same size");
        }

        Set<String> pool = new LinkedHashSet<>();
        boolean anyDuplicate = false;
        for (List<String> units : observedPerSlot) {
            for (String uuid : units) {
                if (!pool.add(uuid)) {
                    anyDuplicate = true;
                }
            }
        }

        if (!anyDuplicate) {
            return observedPerSlot;
        }

        List<List<String>> result = new ArrayList<>(observedPerSlot.size());
        var iterator = pool.iterator();
        for (int amount : targetAmounts) {
            List<String> assigned = new ArrayList<>(Math.max(amount, 0));
            for (int i = 0; i < amount && iterator.hasNext(); i++) {
                assigned.add(iterator.next());
            }
            result.add(assigned);
        }
        return result;
    }

    /**
     * @param destination     units already on the merge target
     * @param source          units being offered to it, front of the list first
     * @param destinationCap  the target's max stack size
     * @param transferLimit   the most units this particular interaction is allowed to move (e.g.
     *                        1 for a vanilla right-click place, or {@code source.size()} for a
     *                        left-click/full move) - independent of how much room is actually left
     */
    public record MergeResult(List<String> destination, List<String> source) {
    }

    /**
     * Two physically identical tracked stacks (same template, different UUID) never look
     * "similar" to Bukkit - every unit's PDC is unique by design - so vanilla can never merge them
     * on its own, whether they fit into one stack or not. This is the pure slicing math behind
     * every hand-built merge in this plugin (direct click, pickup, cursor-gather, inventory
     * consolidation): move as many units as fit - capped by both the destination's remaining room
     * and {@code transferLimit} - from the front of {@code source} onto the end of
     * {@code destination}, leaving the rest on {@code source}. Never loses a unit regardless of
     * whether everything fits, nothing fits, or only part of it does.
     */
    public static MergeResult mergeUnits(List<String> destination, List<String> source,
                                          int destinationCap, int transferLimit) {
        int room = destinationCap - destination.size();
        int transfer = Math.max(0, Math.min(Math.min(room, transferLimit), source.size()));

        List<String> newDestination = new ArrayList<>(destination.size() + transfer);
        newDestination.addAll(destination);
        newDestination.addAll(source.subList(0, transfer));

        List<String> remainingSource = new ArrayList<>(source.subList(transfer, source.size()));
        return new MergeResult(newDestination, remainingSource);
    }
}
