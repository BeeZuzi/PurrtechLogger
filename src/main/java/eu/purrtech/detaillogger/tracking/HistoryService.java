package eu.purrtech.detaillogger.tracking;

import eu.purrtech.detaillogger.db.dao.EventDao;
import eu.purrtech.detaillogger.db.dao.EventRecord;
import eu.purrtech.detaillogger.db.dao.TrackedUnitDao;
import eu.purrtech.detaillogger.db.dao.TrackedUnitRecord;

import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

/**
 * Read side for admin/report lookups. Must be called off the main thread (delegates to DAOs that
 * assert this).
 */
public final class HistoryService {

    private final TrackedUnitDao trackedUnitDao;
    private final EventDao eventDao;

    public HistoryService(TrackedUnitDao trackedUnitDao, EventDao eventDao) {
        this.trackedUnitDao = trackedUnitDao;
        this.eventDao = eventDao;
    }

    public Optional<UnitHistory> lookup(String uuid) throws SQLException {
        TrackedUnitRecord unit = trackedUnitDao.findByUuid(uuid);
        if (unit == null) {
            return Optional.empty();
        }
        List<EventRecord> events = eventDao.findByUnit(uuid);
        return Optional.of(new UnitHistory(unit, events));
    }

    public record UnitHistory(TrackedUnitRecord unit, List<EventRecord> events) {
    }
}
