package eu.purrtech.detaillogger.db.dao;

import eu.purrtech.detaillogger.db.Database;
import eu.purrtech.detaillogger.db.DbTask;
import eu.purrtech.detaillogger.db.MainThreadCheck;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/** Which unit an item was made from or turned into - see V4__unit_lineage.sql. */
public final class LineageDao {

    /** One link. {@code otherUuid} is the unit on the other side (null if that result isn't tracked). */
    public record Link(String otherUuid, String relation, String detail, long at) {
    }

    private final Database database;

    public LineageDao(Database database) {
        this.database = database;
    }

    public void enqueue(String parentUuid, String childUuid, String relation, String detail, long at) {
        database.writeQueue().offer(new DbTask.InsertLineageTask(parentUuid, childUuid, relation, detail, at));
    }

    /** Units this one was made from. Blocking read - must be called off the main thread. */
    public List<Link> findParents(String unitUuid) throws SQLException {
        return query("SELECT parent_uuid, relation, detail, at FROM unit_lineage WHERE child_uuid = ? ORDER BY at",
                unitUuid);
    }

    /** What this unit was turned into. Blocking read - must be called off the main thread. */
    public List<Link> findChildren(String unitUuid) throws SQLException {
        return query("SELECT child_uuid, relation, detail, at FROM unit_lineage WHERE parent_uuid = ? ORDER BY at",
                unitUuid);
    }

    private List<Link> query(String sql, String unitUuid) throws SQLException {
        MainThreadCheck.assertAsync();
        Connection connection;
        try {
            connection = database.readPool().borrow();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SQLException("Interrupted while waiting for a read connection", e);
        }
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, unitUuid);
            try (ResultSet rs = ps.executeQuery()) {
                List<Link> links = new ArrayList<>();
                while (rs.next()) {
                    links.add(new Link(rs.getString(1), rs.getString(2), rs.getString(3), rs.getLong(4)));
                }
                return links;
            }
        } finally {
            database.readPool().release(connection);
        }
    }
}
