package com.orpex.core.importing;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;

/**
 * Empreinte (SHA-256) des colonnes de configuration d'une ligne.
 * Sert à distinguer « modifiée » de « inchangée » lors d'un ré-import (spécification, section 9).
 */
public final class ConfigHash {

    private ConfigHash() {
    }

    public static String of(Map<String, String> cells) {
        StringBuilder canonical = new StringBuilder();
        for (String column : Columns.CONFIG) {
            String raw = cells.get(column);
            String value = raw == null ? "" : raw.trim();
            // Le statut et les oui/non sont comparés sans tenir compte de la casse.
            if (column.equals(Columns.STATUS) || column.equals(Columns.BUSINESS_DAYS_ONLY)
                    || column.equals(Columns.DAILY_VALIDATION) || column.equals(Columns.SEND_MODE)) {
                value = value.toLowerCase(Locale.ROOT);
            }
            canonical.append(column).append('=').append(value).append('\n');
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 indisponible", e);
        }
    }
}
