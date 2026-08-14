package eu.purrtech.detaillogger.tracking;

import eu.purrtech.detaillogger.tracking.pdc.TrackedItemTag;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Bukkit-facing wrapper around {@link StackMath}: reads the affected stacks' currently-tagged
 * units, hands them to the pure reslice algorithm, and writes back whatever changed. No-ops (and
 * touches nothing) when nothing needs fixing, which is the overwhelmingly common case.
 */
public final class StackReconciler {

    private final TrackedItemTag itemTag;

    public StackReconciler(TrackedItemTag itemTag) {
        this.itemTag = itemTag;
    }

    /**
     * @param affectedStacks every ItemStack touched by the same inventory action (e.g. the
     *                       clicked slot and the cursor, or every slot a drag touched). Nulls and
     *                       air are fine and treated as "0 units, amount 0". Mutates the stacks'
     *                       meta in place - callers must write them back to their slots.
     */
    public void reconcile(List<ItemStack> affectedStacks) {
        List<List<String>> observed = new ArrayList<>(affectedStacks.size());
        List<Integer> amounts = new ArrayList<>(affectedStacks.size());
        String templateKey = null;

        for (ItemStack stack : affectedStacks) {
            if (stack == null || stack.getType().isAir()) {
                observed.add(List.of());
                amounts.add(0);
                continue;
            }
            List<UUID> units = itemTag.readUnits(stack);
            observed.add(units.stream().map(UUID::toString).toList());
            amounts.add(stack.getAmount());
            if (templateKey == null) {
                templateKey = itemTag.readTemplateKey(stack);
            }
        }

        List<List<String>> resliced = StackMath.reslice(observed, amounts);
        if (resliced == observed) {
            return; // StackMath returns the same reference when nothing needed fixing
        }

        for (int i = 0; i < affectedStacks.size(); i++) {
            ItemStack stack = affectedStacks.get(i);
            if (stack == null || stack.getType().isAir()) {
                continue;
            }
            List<UUID> assigned = resliced.get(i).stream().map(UUID::fromString).toList();
            itemTag.writeUnits(stack, assigned, templateKey);
        }
    }
}
