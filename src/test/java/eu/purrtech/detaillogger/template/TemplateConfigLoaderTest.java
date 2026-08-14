package eu.purrtech.detaillogger.template;

import org.bukkit.Material;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TemplateConfigLoaderTest {

    @TempDir
    Path tempDir;

    private final TemplateConfigLoader loader = new TemplateConfigLoader(Logger.getLogger("test"));

    @Test
    void parsesValidTemplatesAndSkipsInvalidOnesIndividually() {
        List<TemplateDefinition> defs = load("""
                templates:
                  legendary_sword:
                    material: DIAMOND_SWORD
                    custom-model-data: 1001
                    track-items: true
                    track-blocks: false
                  special_chest:
                    material: CHEST
                    pdc-marker:
                      key: "someplugin:special"
                      value: "true"
                    track-items: true
                    track-blocks: true
                  named_item:
                    material: NETHERITE_INGOT
                    name-pattern: "Rare Ingot"
                    track-items: true
                    track-blocks: false
                  broken_material:
                    material: NOT_A_REAL_MATERIAL
                    track-items: true
                  no_track_flags:
                    material: DIRT
                    track-items: false
                    track-blocks: false
                  default_track_items:
                    material: GOLD_INGOT
                """);

        assertEquals(5, defs.size(), "broken_material should be the only one rejected: " + defs);

        TemplateDefinition sword = find(defs, "legendary_sword");
        assertEquals(Material.DIAMOND_SWORD, sword.material());
        assertEquals(1001, sword.customModelData());
        assertFalse(sword.trackBlocks());

        TemplateDefinition chest = find(defs, "special_chest");
        assertEquals("someplugin:special", chest.pdcMarkerKey());
        assertEquals("true", chest.pdcMarkerValue());

        TemplateDefinition namedItem = find(defs, "named_item");
        assertTrue(namedItem.namePattern().matcher("A Rare Ingot of Doom").find());

        TemplateDefinition defaultTrack = find(defs, "default_track_items");
        assertTrue(defaultTrack.trackItems(), "track-items should default to true");
        assertFalse(defaultTrack.trackBlocks(), "track-blocks should default to false");
    }

    /**
     * Regression test: an unparseable regex must reject the WHOLE template, not silently fall
     * back to "no name/lore filter" - that would widen matching to every item of that material
     * (e.g. every STONE block) instead of the narrow set the admin intended. Caught during Phase 2
     * development via this exact scenario.
     */
    @Test
    void invalidRegex_rejectsWholeTemplateRatherThanWideningMatch() {
        List<TemplateDefinition> defs = load("""
                templates:
                  broken_regex:
                    material: STONE
                    name-pattern: "("
                    track-items: true
                """);

        assertTrue(defs.isEmpty(), "a template with an unparseable regex must not be loaded at all: " + defs);
    }

    @Test
    void missingFile_returnsEmptyList() {
        TemplateConfigLoader freshLoader = new TemplateConfigLoader(Logger.getLogger("test"));
        List<TemplateDefinition> defs = freshLoader.load(tempDir.resolve("does-not-exist.yml").toFile());
        assertTrue(defs.isEmpty());
    }

    @Test
    void missingMaterial_isSkipped() {
        List<TemplateDefinition> defs = load("""
                templates:
                  no_material:
                    track-items: true
                """);
        assertTrue(defs.isEmpty());
    }

    private static TemplateDefinition find(List<TemplateDefinition> defs, String key) {
        return defs.stream().filter(d -> d.key().equals(key)).findFirst()
                .orElseThrow(() -> new AssertionError("missing key " + key));
    }

    private List<TemplateDefinition> load(String yaml) {
        try {
            Path file = Files.createTempFile(tempDir, "templates", ".yml");
            Files.writeString(file, yaml);
            return loader.load(file.toFile());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
