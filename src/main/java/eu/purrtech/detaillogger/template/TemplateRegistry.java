package eu.purrtech.detaillogger.template;

import eu.purrtech.detaillogger.db.MainThreadCheck;
import eu.purrtech.detaillogger.db.dao.TemplateDao;

import java.io.File;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Holds the currently-loaded template set and their DB ids. {@link #reloadBlocking()} must run
 * off the main thread: it enqueues upserts through the async write pipeline, then polls the read
 * pool until every template's id is confirmed persisted.
 */
public final class TemplateRegistry {

    /**
     * System template key used purely as an FK anchor for items nested inside a shulker box
     * that's itself being carried as an item (see {@code ItemTrackingService#ensureContainerAnchor}).
     * Registered with {@code track-items=false} so it's never matched via the normal genesis path -
     * it exists only to give shulker "containers" their own tracked_units row.
     */
    public static final String SHULKER_ANCHOR_KEY = "__shulker_anchor__";

    private static final int MAX_POLL_ATTEMPTS = 40;
    private static final long POLL_INTERVAL_MS = 50;

    private final File templatesFile;
    private final TemplateConfigLoader loader;
    private final TemplateDao templateDao;
    private final Logger logger;

    private volatile List<TemplateDefinition> definitions = List.of();
    private final Map<String, Integer> templateIds = new ConcurrentHashMap<>();

    public TemplateRegistry(File templatesFile, TemplateDao templateDao, Logger logger) {
        this.templatesFile = templatesFile;
        this.loader = new TemplateConfigLoader(logger);
        this.templateDao = templateDao;
        this.logger = logger;
    }

    public void reloadBlocking() throws SQLException, InterruptedException {
        MainThreadCheck.assertAsync();

        List<TemplateDefinition> loaded = loader.load(templatesFile);
        long now = System.currentTimeMillis();
        for (TemplateDefinition def : loaded) {
            templateDao.enqueueUpsert(
                    def.key(),
                    def.material().name(),
                    def.customModelData(),
                    def.pdcMarkerKey(),
                    def.pdcMarkerValue(),
                    def.namePattern() != null ? def.namePattern().pattern() : null,
                    def.lorePattern() != null ? def.lorePattern().pattern() : null,
                    def.trackItems(),
                    def.trackBlocks(),
                    now);
        }
        // System template: never matched via TemplateMatcher (track-items/blocks both false),
        // exists only so shulker "container anchors" have a valid template_id FK to reference.
        templateDao.enqueueUpsert(SHULKER_ANCHOR_KEY, "SHULKER_BOX", null, null, null, null, null,
                false, false, now);

        List<String> keys = new ArrayList<>(loaded.stream().map(TemplateDefinition::key).toList());
        keys.add(SHULKER_ANCHOR_KEY);
        Map<String, Integer> ids = Map.of();
        for (int attempt = 0; attempt < MAX_POLL_ATTEMPTS; attempt++) {
            Map<String, Integer> found = templateDao.findIdsByKeys(keys);
            if (found.size() == keys.size()) {
                ids = found;
                break;
            }
            Thread.sleep(POLL_INTERVAL_MS);
        }

        if (ids.size() < keys.size()) {
            logger.warning("Nepodarilo se potvrdit id vsech sablon v DB po "
                    + (MAX_POLL_ATTEMPTS * POLL_INTERVAL_MS) + "ms - zkus /purrlog reload znovu");
            ids = templateDao.findIdsByKeys(keys);
        }

        this.definitions = List.copyOf(loaded);
        this.templateIds.clear();
        this.templateIds.putAll(ids);

        boolean systemTemplateConfirmed = this.templateIds.containsKey(SHULKER_ANCHOR_KEY);
        int userConfirmed = this.templateIds.size() - (systemTemplateConfirmed ? 1 : 0);
        logger.info("Nacteno " + loaded.size() + " sablon (" + userConfirmed + " potvrzeno v DB, "
                + (systemTemplateConfirmed ? "systemova sablona OK" : "systemova sablona CHYBI") + ")");
    }

    public List<TemplateDefinition> definitions() {
        return definitions;
    }

    public Integer idOf(String templateKey) {
        return templateIds.get(templateKey);
    }
}
