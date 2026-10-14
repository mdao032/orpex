package com.orpex.crypto;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Chiffrement des secrets au repos (mot de passe SMTP, spécification §10) : AES-256-GCM, chiffrement authentifié.
 * La clé vient de la configuration ({@code ORPEX_SECRET_KEY}, 32 octets en Base64) et n'est jamais stockée en base.
 *
 * <p>Format stocké : 1 octet de version, 12 octets d'IV aléatoire, puis le texte chiffré et son étiquette.
 */
public final class SecretCipher {

    private static final byte VERSION = 1;
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final SecretKey key;

    /** {@code base64Key} vide : aucun secret ne peut être chiffré ni déchiffré. */
    public SecretCipher(String base64Key) {
        if (base64Key == null || base64Key.isBlank()) {
            this.key = null;
            return;
        }
        byte[] raw;
        try {
            raw = Base64.getDecoder().decode(base64Key.strip());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("orpex.secret-key (ORPEX_SECRET_KEY) invalide : Base64 attendu.", e);
        }
        if (raw.length != 32) {
            throw new IllegalStateException("orpex.secret-key (ORPEX_SECRET_KEY) invalide : 32 octets attendus "
                    + "(256 bits, en Base64), " + raw.length + " reçus.");
        }
        this.key = new SecretKeySpec(raw, "AES");
    }

    public boolean isAvailable() {
        return key != null;
    }

    public byte[] encrypt(String secret) {
        requireKey();
        byte[] iv = new byte[IV_BYTES];
        RANDOM.nextBytes(iv);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] encrypted = cipher.doFinal(secret.getBytes(StandardCharsets.UTF_8));
            return ByteBuffer.allocate(1 + IV_BYTES + encrypted.length).put(VERSION).put(iv).put(encrypted).array();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Chiffrement impossible", e);
        }
    }

    public String decrypt(byte[] stored) {
        requireKey();
        if (stored == null || stored.length <= 1 + IV_BYTES || stored[0] != VERSION) {
            throw new IllegalStateException("Secret chiffré illisible");
        }
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, stored, 1, IV_BYTES));
            byte[] plain = cipher.doFinal(stored, 1 + IV_BYTES, stored.length - 1 - IV_BYTES);
            return new String(plain, StandardCharsets.UTF_8);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Secret indéchiffrable : la clé ORPEX_SECRET_KEY a-t-elle changé ?", e);
        }
    }

    private void requireKey() {
        if (key == null) {
            throw new IllegalStateException("Aucune clé de chiffrement : définir ORPEX_SECRET_KEY.");
        }
    }
}
