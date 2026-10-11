package com.orpex.core.calendar;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.Collection;
import java.util.Set;

/** Jours ouvrés : du lundi au vendredi, hors jours fériés configurés par l'administrateur. */
public final class BusinessCalendar {

    private static final int MAX_LOOKBACK_DAYS = 366;

    private final Set<LocalDate> holidays;

    public BusinessCalendar(Collection<LocalDate> holidays) {
        this.holidays = Set.copyOf(holidays);
    }

    public static BusinessCalendar weekendsOnly() {
        return new BusinessCalendar(Set.of());
    }

    public boolean isBusinessDay(LocalDate day) {
        DayOfWeek dow = day.getDayOfWeek();
        return dow != DayOfWeek.SATURDAY && dow != DayOfWeek.SUNDAY && !holidays.contains(day);
    }

    /** Renvoie {@code day} s'il est ouvré, sinon le jour ouvré le plus proche avant lui. */
    public LocalDate lastBusinessDayOnOrBefore(LocalDate day) {
        LocalDate candidate = day;
        for (int i = 0; i <= MAX_LOOKBACK_DAYS; i++) {
            if (isBusinessDay(candidate)) {
                return candidate;
            }
            candidate = candidate.minusDays(1);
        }
        throw new IllegalStateException("Aucun jour ouvré trouvé avant " + day + " : calendrier incohérent");
    }
}
