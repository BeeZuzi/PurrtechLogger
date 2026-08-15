package eu.purrtech.detaillogger.tracking;

import eu.purrtech.detaillogger.db.dao.DupeAlertDao;
import eu.purrtech.detaillogger.db.dao.DupeAlertRecord;
import eu.purrtech.detaillogger.db.dao.EventDao;
import eu.purrtech.detaillogger.db.dao.EventRecord;
import eu.purrtech.detaillogger.db.dao.PlayerDao;
import eu.purrtech.detaillogger.db.dao.PlayerNameHistoryRecord;
import eu.purrtech.detaillogger.db.dao.PlayerRecord;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.logging.Logger;

/**
 * Directory of every player who has ever joined - both the join/quit presence tracking (write
 * side) and the admin-GUI/report lookups (read side, off the main thread like
 * {@link HistoryService}).
 */
public final class PlayerDirectoryService {

    private final PlayerDao playerDao;
    private final EventDao eventDao;
    private final DupeAlertDao dupeAlertDao;
    private final Plugin plugin;
    private final Logger logger;

    public PlayerDirectoryService(PlayerDao playerDao, EventDao eventDao, DupeAlertDao dupeAlertDao,
                                   Plugin plugin, Logger logger) {
        this.playerDao = playerDao;
        this.eventDao = eventDao;
        this.dupeAlertDao = dupeAlertDao;
        this.plugin = plugin;
        this.logger = logger;
    }

    /**
     * Must be called on the main thread. Hops async to compare the incoming name against what's
     * on record - a new player or a changed nickname gets an extra name-history row alongside the
     * join upsert; an unchanged nickname just refreshes presence.
     */
    public void recordJoin(String uuid, String name, long now) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                PlayerRecord existing = playerDao.findByUuid(uuid);
                if (existing == null || !name.equals(existing.currentName())) {
                    playerDao.enqueueNameChange(uuid, name, now);
                }
            } catch (SQLException e) {
                logger.severe("Kontrola nicku pri joinu selhala pro " + uuid + ": " + e);
            }
            playerDao.enqueueUpsertOnJoin(uuid, name, now);
        });
    }

    /** Fire-and-forget, no read-before-write needed - safe to call directly on the main thread. */
    public void recordQuit(String uuid, long now) {
        playerDao.enqueueSetOffline(uuid, now);
    }

    /**
     * Blocking read - must be called off the main thread.
     */
    public List<PlayerRecord> listPlayers() throws SQLException {
        return playerDao.findAll();
    }

    /**
     * Blocking read - must be called off the main thread.
     */
    public List<PlayerRecord> searchPlayers(String query) throws SQLException {
        return playerDao.search(query);
    }

    /**
     * Blocking read - must be called off the main thread.
     */
    public Optional<PlayerProfile> lookupPlayer(String uuid) throws SQLException {
        PlayerRecord player = playerDao.findByUuid(uuid);
        if (player == null) {
            return Optional.empty();
        }
        List<PlayerNameHistoryRecord> nameHistory = playerDao.findNameHistory(uuid);
        return Optional.of(new PlayerProfile(player, nameHistory));
    }

    /**
     * Blocking read - must be called off the main thread. Events and dupe alerts are kept
     * separate rather than merged into one list, since the admin GUI renders creative-vs-bug dupe
     * attempts differently from an ordinary placed/destroyed/dropped event.
     */
    public PlayerActivity findActivity(String uuid, int eventLimit) throws SQLException {
        List<EventRecord> events = eventDao.findByPlayer(uuid, eventLimit);
        List<DupeAlertRecord> alerts = dupeAlertDao.findByPlayer(uuid);
        return new PlayerActivity(events, alerts);
    }

    public record PlayerProfile(PlayerRecord player, List<PlayerNameHistoryRecord> nameHistory) {
    }

    public record PlayerActivity(List<EventRecord> events, List<DupeAlertRecord> alerts) {
    }
}
