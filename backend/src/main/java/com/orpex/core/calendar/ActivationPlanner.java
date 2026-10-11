package com.orpex.core.calendar;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Sépare les jours d'envoi à l'activation d'une ligne (spécification, section 6) :
 * les jours passés ne sont pas rattrapés, et un envoi du jour dont l'heure est déjà passée est marqué « manqué ».
 */
public final class ActivationPlanner {

    private ActivationPlanner() {
    }

    public record Split(List<LocalDate> planned, List<LocalDate> missed) {
    }

    public static Split split(List<LocalDate> sendDates, LocalTime sendTime, ZoneId zone, Instant now) {
        ZonedDateTime current = now.atZone(zone);
        LocalDate today = current.toLocalDate();
        List<LocalDate> planned = new ArrayList<>();
        List<LocalDate> missed = new ArrayList<>();

        for (LocalDate day : sendDates) {
            if (day.isBefore(today)) {
                continue; // pas de rattrapage du passé
            }
            if (day.equals(today) && !sendTime.isAfter(current.toLocalTime())) {
                missed.add(day);
            } else {
                planned.add(day);
            }
        }
        return new Split(List.copyOf(planned), List.copyOf(missed));
    }
}
