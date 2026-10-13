package eu.purrtech.detaillogger.tracking.listener;

import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommandVanishListenerTest {

    @Test
    void labelIgnoresSlashCaseAndNamespace() {
        assertEquals("ah", CommandVanishListener.labelOf("/ah sell 100"));
        assertEquals("ah", CommandVanishListener.labelOf("/Essentials:AH sell 100"));
        assertEquals("pay", CommandVanishListener.labelOf("pay Bob 5"));
    }

    @Test
    void credentialsCommandsAreNeverRecorded() {
        assertTrue(CommandVanishListener.isSensitive("/login hunter2"));
        assertTrue(CommandVanishListener.isSensitive("/AuthMe:Register pw pw"));
        assertTrue(CommandVanishListener.isSensitive("/changepassword old new"));
        assertFalse(CommandVanishListener.isSensitive("/ah sell 100"));
    }

    @Test
    void longCommandsAreCutAndTheSlashDropped() {
        assertEquals("ah sell 100", CommandVanishListener.shorten("/ah sell 100"));
        String shortened = CommandVanishListener.shorten("/say " + "x".repeat(500));
        assertEquals(123, shortened.length()); // 120 characters + "..."
        assertTrue(shortened.endsWith("..."));
    }

    @Test
    void missingIsWhatWasCarriedBeforeButNotAfter() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        UUID c = UUID.randomUUID();
        assertEquals(Set.of(a), CommandVanishListener.missing(Set.of(a, b), Set.of(b, c)));
        assertTrue(CommandVanishListener.missing(Set.of(a), Set.of(a)).isEmpty());
        assertTrue(CommandVanishListener.missing(Set.of(), Set.of(a)).isEmpty()); // gaining something is not a vanish
    }
}
