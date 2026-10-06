package eu.purrtech.detaillogger.db;

/**
 * How much the server owner cares about an event type (config.yml {@code event-priority}). Decides
 * what is dropped first when the write queue backs up: a task is only accepted while the queue is
 * less full than its priority's {@link #fillLimit}, so under load LOWEST goes first and HIGHEST
 * is kept until the queue is truly full. {@link #OFF} is never stored at all.
 */
public enum WritePriority {
    HIGHEST(1.00),
    HIGH(0.92),
    MEDIUM(0.80),
    LOW(0.65),
    LOWEST(0.50),
    OFF(0.0);

    private final double fillLimit;

    WritePriority(double fillLimit) {
        this.fillLimit = fillLimit;
    }

    /** Max number of pending bulk tasks at which this priority is still accepted. */
    long limit(long capacity) {
        return (long) (capacity * fillLimit);
    }
}
