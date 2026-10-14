package com.orpex.crypto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class SecretCipherTest {

    private static final String KEY = "9TQpPMTx2Mavnc298//hI+jtMp0dsix7w0mO4uLJw4o=";
    private static final String OTHER_KEY = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=";

    @Test
    void encryptsAndDecryptsWithoutTheSecretAppearingInClear() {
        SecretCipher cipher = new SecretCipher(KEY);

        byte[] stored = cipher.encrypt("mot-de-passe-relais-é");

        assertEquals("mot-de-passe-relais-é", cipher.decrypt(stored));
        String asText = new String(stored, StandardCharsets.ISO_8859_1);
        assertFalse(asText.contains("mot-de-passe"));
        assertFalse(Arrays.equals(stored, cipher.encrypt("mot-de-passe-relais-é")), "IV aléatoire à chaque fois");
    }

    @Test
    void aTamperedValueOrAnotherKeyCannotDecrypt() {
        byte[] stored = new SecretCipher(KEY).encrypt("secret");
        byte[] tampered = stored.clone();
        tampered[tampered.length - 1] ^= 1;

        assertThrows(IllegalStateException.class, () -> new SecretCipher(KEY).decrypt(tampered));
        var wrongKey = assertThrows(IllegalStateException.class, () -> new SecretCipher(OTHER_KEY).decrypt(stored));
        assertTrue(wrongKey.getMessage().contains("ORPEX_SECRET_KEY"));
        assertNotEquals(KEY, OTHER_KEY);
    }

    @Test
    void withoutKeyNothingCanBeEncryptedAndABadKeyIsRefused() {
        SecretCipher none = new SecretCipher("");
        assertFalse(none.isAvailable());
        assertThrows(IllegalStateException.class, () -> none.encrypt("secret"));

        var tooShort = assertThrows(IllegalStateException.class, () -> new SecretCipher("c2hvcnQ="));
        assertTrue(tooShort.getMessage().contains("32 octets attendus"));
        assertThrows(IllegalStateException.class, () -> new SecretCipher("pas du base64 !"));
    }
}
