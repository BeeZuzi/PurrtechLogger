package eu.purrtech.detaillogger.template;

import org.bukkit.Material;

import java.util.regex.Pattern;

/**
 * Immutable, config-facing definition of what makes an item/block "worth tracking". At most one
 * of {@code pdcMarkerKey}/{@code pdcMarkerValue} pair is set together, never just one.
 */
public record TemplateDefinition(
        String key,
        Material material,
        Integer customModelData,
        String pdcMarkerKey,
        String pdcMarkerValue,
        Pattern namePattern,
        Pattern lorePattern,
        boolean trackItems,
        boolean trackBlocks,
        /** {@code mode: ledger} - items are counted, not tagged: no UUID, they stack like vanilla. */
        boolean ledger,
        /**
         * The item the template was created from, exactly as it was (Base64 of
         * {@code ItemStack#serializeAsBytes}, one piece, tracking tags removed) - what
         * {@code /purrlog give} hands out. Null for templates written by hand or created before the
         * snapshot existed; see {@link TemplateItems}.
         */
        String itemSnapshot
) {
}
