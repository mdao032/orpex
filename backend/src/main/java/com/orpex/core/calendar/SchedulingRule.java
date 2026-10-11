package com.orpex.core.calendar;

import java.time.LocalDate;
import java.util.Objects;

/**
 * Partie « calendrier » d'une ligne du fichier : tout ce qu'il faut pour calculer les jours d'envoi.
 * Les champs non utilisés par le mode choisi sont {@code null}.
 */
public record SchedulingRule(
        SendMode mode,
        LocalDate sendDate,
        LocalDate dueDate,
        Integer daysBefore,
        LocalDate startDate,
        LocalDate endDate,
        boolean businessDaysOnly) {

    public static SchedulingRule once(LocalDate sendDate, boolean businessDaysOnly) {
        Objects.requireNonNull(sendDate, "sendDate");
        return new SchedulingRule(SendMode.PONCTUEL, sendDate, null, null, null, null, businessDaysOnly);
    }

    public static SchedulingRule beforeDue(LocalDate dueDate, int daysBefore, boolean businessDaysOnly) {
        Objects.requireNonNull(dueDate, "dueDate");
        if (daysBefore < 0) {
            throw new IllegalArgumentException("jours_avant doit être >= 0");
        }
        return new SchedulingRule(SendMode.AVANT_ECHEANCE, null, dueDate, daysBefore, null, null, businessDaysOnly);
    }

    public static SchedulingRule period(LocalDate startDate, LocalDate endDate, boolean businessDaysOnly) {
        Objects.requireNonNull(startDate, "startDate");
        Objects.requireNonNull(endDate, "endDate");
        if (endDate.isBefore(startDate)) {
            throw new IllegalArgumentException("date_fin doit être >= date_debut");
        }
        return new SchedulingRule(SendMode.PERIODE, null, null, null, startDate, endDate, businessDaysOnly);
    }
}
