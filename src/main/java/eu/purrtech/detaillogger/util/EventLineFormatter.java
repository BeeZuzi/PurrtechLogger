package eu.purrtech.detaillogger.util;

import eu.purrtech.detaillogger.db.dao.EventRecord;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Shared human-readable formatting for {@link EventRecord}s and timestamps, used by both
 * {@code PurrLogCommand}'s text output and {@code AdminGuiService}'s history/activity panels, so
 * the two don't drift into subtly different formats.
 */
public final class EventLineFormatter {

    private static final DateTimeFormatter TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());
    private static final Pattern DETAIL_FIELD = Pattern.compile("\"([^\"]+)\":\"([^\"]*)\"");

    private EventLineFormatter() {
    }

    public static String formatTime(Long epochMillis) {
        return epochMillis == null ? "?" : TIME_FORMAT.format(Instant.ofEpochMilli(epochMillis));
    }

    /** {@code "@ world x,y,z"}, or {@code ""} if the event carries no coordinates. */
    public static String formatLocation(EventRecord e) {
        return e.world() != null ? "@ " + e.world() + " " + e.x() + "," + e.y() + "," + e.z() : "";
    }

    /**
     * Turns the flat {@code {"field":"value"}}-style detail blobs this plugin writes (e.g.
     * {@code {"cause":"LAVA"}}) into {@code "cause=LAVA"} - a tiny hand-rolled parser instead of a
     * JSON dependency, since every detail blob here is produced by this same plugin's own
     * jsonField()-style helpers and is never nested.
     */
    public static String formatDetail(String detailJson) {
        if (detailJson == null || detailJson.isBlank()) {
            return "";
        }
        Matcher matcher = DETAIL_FIELD.matcher(detailJson);
        List<String> parts = new ArrayList<>();
        while (matcher.find()) {
            parts.add(matcher.group(1) + "=" + matcher.group(2));
        }
        return String.join(", ", parts);
    }

    /**
     * One consistent, compact line: {@code [2026-08-16 14:07:16] DESTROYED @ world 0,68,1
     * (cause=LAVA) [SURVIVAL]}. Location/detail/gamemode segments are omitted entirely when the
     * event doesn't carry them, instead of leaving stray blank spaces.
     */
    public static String formatLine(EventRecord e) {
        StringBuilder line = new StringBuilder();
        line.append('[').append(formatTime(e.timestamp())).append("] ").append(e.eventType());

        String where = formatLocation(e);
        if (!where.isEmpty()) {
            line.append(' ').append(where);
        }
        String detail = formatDetail(e.detail());
        if (!detail.isEmpty()) {
            line.append(" (").append(detail).append(')');
        }
        if (e.gamemode() != null) {
            line.append(" [").append(e.gamemode()).append(']');
        }
        return line.toString();
    }
}
