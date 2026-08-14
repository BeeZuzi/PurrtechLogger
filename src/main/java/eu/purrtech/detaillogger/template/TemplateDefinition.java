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
        boolean trackBlocks
) {
}
