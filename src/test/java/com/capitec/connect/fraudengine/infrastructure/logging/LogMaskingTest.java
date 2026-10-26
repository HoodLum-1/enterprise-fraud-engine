package com.capitec.connect.fraudengine.infrastructure.logging;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LogMaskingTest {

    @Test
    void anAccountIdIsNotWrittenToTheLogInFull() {
        String masked = LogMasking.mask("ACC-1234567890");

        assertFalse(masked.contains("1234567890"), "the whole account id must not reach the log");
        assertEquals("ACC*********90", masked);
    }

    @Test
    void enoughOfTheEndsSurvivesToCorrelateTwoLines() {
        // An operator reading a stack of lines still has to be able to tell that two of them are
        // about the same account, or the log stops being useful for the thing it is for.
        assertEquals(LogMasking.mask("ACC-999888777"), LogMasking.mask("ACC-999888777"));
        assertFalse(LogMasking.mask("ACC-999888777").equals(LogMasking.mask("ACC-999888778")));
    }

    @Test
    void aShortValueIsReplacedRatherThanPartlyShown() {
        // Below the length where a prefix and suffix would leave anything worth reading.
        assertEquals("***", LogMasking.mask("abc"));
        assertEquals("*", LogMasking.mask("a"));
    }

    @Test
    void anAbsentAccountIdIsNamedRatherThanBlank() {
        assertEquals("<none>", LogMasking.mask(""));
        assertEquals("<none>", LogMasking.mask("   "));
    }

    @Test
    void aMissingAccountIdIsRefusedRatherThanSilentlyLogged() {
        assertThrows(NullPointerException.class, () -> LogMasking.mask(null));
    }

    @Test
    void aRealisticAccAccountIdKeepsOnlyItsShape() {
        String masked = LogMasking.mask("ACC-INT-000123456");

        assertTrue(masked.startsWith("ACC"), "the prefix keeps the id recognisable as an account");
        assertTrue(masked.endsWith("56"), "the suffix keeps it distinguishable from its neighbours");
        assertFalse(masked.contains("123456"), "the digits that identify the customer are gone");
    }
}
