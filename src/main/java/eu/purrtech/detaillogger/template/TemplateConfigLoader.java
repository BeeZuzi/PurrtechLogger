package eu.purrtech.detaillogger.template;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Parses {@code templates.yml} into {@link TemplateDefinition}s. Invalid entries are logged and
 * skipped rather than failing the whole load - one bad template shouldn't take down the rest.
 */
public final class TemplateConfigLoader {

    private final Logger logger;

    public TemplateConfigLoader(Logger logger) {
        this.logger = logger;
    }

    public List<TemplateDefinition> load(File file) {
        List<TemplateDefinition> result = new ArrayList<>();
        if (!file.exists()) {
            return result;
        }

        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection templatesSection = yaml.getConfigurationSection("templates");
        if (templatesSection == null) {
            return result;
        }

        for (String key : templatesSection.getKeys(false)) {
            ConfigurationSection section = templatesSection.getConfigurationSection(key);
            if (section == null) {
                continue;
            }
            TemplateDefinition definition = parseOne(key, section);
            if (definition != null) {
                result.add(definition);
            }
        }
        return result;
    }

    private TemplateDefinition parseOne(String key, ConfigurationSection section) {
        String materialName = section.getString("material");
        if (materialName == null) {
            logger.warning("templates.yml: '" + key + "' nema 'material', preskakuji");
            return null;
        }
        Material material = Material.matchMaterial(materialName);
        if (material == null) {
            logger.warning("templates.yml: '" + key + "' ma neplatny material '" + materialName + "', preskakuji");
            return null;
        }

        Integer customModelData = section.contains("custom-model-data")
                ? section.getInt("custom-model-data")
                : null;

        String pdcMarkerKey = null;
        String pdcMarkerValue = null;
        ConfigurationSection markerSection = section.getConfigurationSection("pdc-marker");
        if (markerSection != null) {
            pdcMarkerKey = markerSection.getString("key");
            pdcMarkerValue = markerSection.getString("value");
            if (pdcMarkerKey == null || pdcMarkerValue == null) {
                logger.warning("templates.yml: '" + key + "' ma neuplny pdc-marker (potreba key i value), ignoruji ho");
                pdcMarkerKey = null;
                pdcMarkerValue = null;
            }
        }

        // A pattern that fails to compile must reject the whole template rather than silently
        // fall back to "no name/lore filter" - that would widen matching to every item of that
        // material (e.g. every STONE block) instead of the narrow set the admin intended.
        String rawNamePattern = section.getString("name-pattern");
        Pattern namePattern = null;
        if (rawNamePattern != null) {
            namePattern = compilePattern(key, rawNamePattern, "name-pattern");
            if (namePattern == null) {
                return null;
            }
        }

        String rawLorePattern = section.getString("lore-pattern");
        Pattern lorePattern = null;
        if (rawLorePattern != null) {
            lorePattern = compilePattern(key, rawLorePattern, "lore-pattern");
            if (lorePattern == null) {
                return null;
            }
        }

        boolean trackItems = section.getBoolean("track-items", true);
        boolean trackBlocks = section.getBoolean("track-blocks", false);

        if (!trackItems && !trackBlocks) {
            logger.warning("templates.yml: '" + key + "' ma track-items i track-blocks na false, nebude nic sledovat");
        }

        // mode: unit (default) = every item gets its own UUID. mode: ledger = stackable commodity
        // (keys, money, diamonds): not tagged at all, only counted - see LedgerService.
        String mode = section.getString("mode", "unit");
        boolean ledger = "ledger".equalsIgnoreCase(mode);
        if (!ledger && !"unit".equalsIgnoreCase(mode)) {
            logger.warning("templates.yml: '" + key + "' ma neznamy mode '" + mode + "' (unit/ledger), pouzivam unit");
        }
        if (ledger && trackBlocks) {
            logger.warning("templates.yml: '" + key + "' mode ledger plati jen pro itemy, track-blocks se ignoruje");
        }

        return new TemplateDefinition(key, material, customModelData, pdcMarkerKey, pdcMarkerValue,
                namePattern, lorePattern, trackItems, trackBlocks, ledger);
    }

    private Pattern compilePattern(String templateKey, String raw, String fieldName) {
        if (raw == null) {
            return null;
        }
        try {
            return Pattern.compile(raw);
        } catch (PatternSyntaxException e) {
            logger.warning("templates.yml: '" + templateKey + "' ma neplatny regex v '" + fieldName + "': " + e.getMessage());
            return null;
        }
    }
}
