package eu.purrtech.detaillogger.tracking;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every tracked unit carries its own distinct PDC, so a naive Bukkit stack split (e.g.
 * right-click take-half) clones that PDC onto both resulting stacks - the same unit briefly
 * appears to exist twice. This is the pure algorithm that detects and repairs that, with no
 * Bukkit dependency, so it's directly testable.
 */
class StackMathTest {

    @Test
    void noDuplicates_passesThroughUnchanged() {
        List<List<String>> input = List.of(List.of("A"), List.of("B"));
        List<List<String>> result = StackMath.reslice(input, List.of(1, 1));

        assertSame(input, result, "no-duplicate case should return the same reference, not a copy");
    }

    @Test
    void splitClone_reslicesByActualAmount() {
        // Both resulting stacks wrongly carry the full original [A, B, C] list, but Bukkit
        // correctly set the amounts to 1 and 2.
        List<List<String>> observed = List.of(List.of("A", "B", "C"), List.of("A", "B", "C"));
        List<List<String>> result = StackMath.reslice(observed, List.of(1, 2));

        assertEquals(List.of("A"), result.get(0));
        assertEquals(List.of("B", "C"), result.get(1));
        assertTrue(disjoint(result.get(0), result.get(1)), "no unit should appear in both slots after reslice");
    }

    @Test
    void threeWayDragSpread_distributesEachUnitToExactlyOneTarget() {
        // All three drag targets wrongly cloned the same [X, Y, Z].
        List<List<String>> observed = List.of(
                List.of("X", "Y", "Z"), List.of("X", "Y", "Z"), List.of("X", "Y", "Z"));
        List<List<String>> result = StackMath.reslice(observed, List.of(1, 1, 1));

        Set<String> flattened = new HashSet<>();
        for (List<String> slot : result) {
            assertEquals(1, slot.size(), "each drag target should end up with exactly 1 unit: " + slot);
            flattened.addAll(slot);
        }
        assertEquals(3, flattened.size(), "all 3 units should be distinct across drag targets: " + result);
    }

    @Test
    void zeroAmountTarget_getsNothing() {
        List<List<String>> observed = List.of(List.of("P", "Q"), List.of("P", "Q"));
        List<List<String>> result = StackMath.reslice(observed, List.of(2, 0));

        assertEquals(List.of("P", "Q"), result.get(0));
        assertTrue(result.get(1).isEmpty());
    }

    @Test
    void distinctUnitsAcrossStacks_neverTouched() {
        // A genuine merge target (two different single-unit stacks) has no duplication - nothing
        // should be reassigned.
        List<List<String>> input = List.of(List.of("M1"), List.of("M2"));
        List<List<String>> result = StackMath.reslice(input, List.of(1, 1));

        assertEquals(List.of("M1"), result.get(0));
        assertEquals(List.of("M2"), result.get(1));
    }

    private static boolean disjoint(List<String> a, List<String> b) {
        for (String item : a) {
            if (b.contains(item)) {
                return false;
            }
        }
        return true;
    }

    @Test
    void mismatchedListSizes_rejected() {
        assertThrows(IllegalArgumentException.class,
                () -> StackMath.reslice(List.of(List.of("A")), List.of(1, 2)));
    }
}
