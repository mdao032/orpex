package com.orpex.core.calendar;

import java.util.Locale;

/** Les trois modes d'envoi décrits dans la spécification (section 6). */
public enum SendMode {
    /** Un seul envoi, à une date donnée. */
    PONCTUEL,
    /** Un envoi quotidien de (échéance - N jours) à l'échéance, bornes incluses. */
    AVANT_ECHEANCE,
    /** Un envoi quotidien de la date de début à la date de fin, bornes incluses. */
    PERIODE;

    /** Convertit la valeur saisie dans la colonne {@code mode_envoi} du fichier Excel. */
    public static SendMode fromFileValue(String value) {
        if (value == null) {
            throw new IllegalArgumentException("mode d'envoi absent");
        }
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "ponctuel" -> PONCTUEL;
            case "avant_echeance" -> AVANT_ECHEANCE;
            case "periode" -> PERIODE;
            default -> throw new IllegalArgumentException("mode d'envoi inconnu : " + value);
        };
    }
}
