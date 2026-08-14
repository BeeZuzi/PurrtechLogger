package eu.purrtech.detaillogger.db;

import org.bukkit.Bukkit;

/**
 * Guards blocking DAO query methods from accidentally running on the main server thread.
 */
public final class MainThreadCheck {

    private MainThreadCheck() {
    }

    public static void assertAsync() {
        if (Bukkit.isPrimaryThread()) {
            throw new IllegalStateException("Blocking database query must not run on the main server thread");
        }
    }
}
