package de.nucleus.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PasswordsTest {

    @Test
    void verifiesCorrectPasswordOnly() {
        String stored = Passwords.hash("Geheim123");
        assertTrue(Passwords.verify("Geheim123", stored));
        assertFalse(Passwords.verify("geheim123", stored), "Groß-/Kleinschreibung zählt");
        assertFalse(Passwords.verify("", stored));
    }

    @Test
    void neverStoresPlainTextAndUsesSalt() {
        String a = Passwords.hash("truhe");
        String b = Passwords.hash("truhe");
        assertFalse(a.contains("truhe"));
        assertNotEquals(a, b, "gleiches Passwort, anderer Salt");
        assertTrue(Passwords.verify("truhe", a) && Passwords.verify("truhe", b));
    }

    @Test
    void rejectsBrokenStoredValues() {
        assertFalse(Passwords.verify("x", ""));
        assertFalse(Passwords.verify("x", "pbkdf2$abc$def$ghi"));
        assertFalse(Passwords.verify("x", "md5$1$2$3"));
        assertFalse(Passwords.verify("x", "pbkdf2$1000$%%%$%%%"));
    }

    @Test
    void handlesUmlautsAndUnicode() {
        String stored = Passwords.hash("Schlüssel-Größe_ß");
        assertTrue(Passwords.verify("Schlüssel-Größe_ß", stored));
    }
}
