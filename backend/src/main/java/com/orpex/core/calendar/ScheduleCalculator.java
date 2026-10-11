package com.orpex.core.calendar;

import java.time.LocalDate;
import java.util.List;
import java.util.TreeSet;

/**
 * Calcule la liste exacte des jours d'envoi d'une règle (spécification, section 6).
 *
 * <p>Décisions à confirmer avec l'utilisateur final (spécification, section 15) :
 * <ul>
 *   <li>si l'échéance n'est pas ouvrée, le dernier envoi est reporté au dernier jour ouvré précédent
 *       (sans doublon si ce jour a déjà un envoi) ;</li>
 *   <li>un envoi ponctuel tombant un jour non ouvré, avec « jours ouvrés uniquement », est reporté de la même façon
 *       plutôt que supprimé en silence.</li>
 * </ul>
 */
public final class ScheduleCalculator {

    private final BusinessCalendar calendar;

    public ScheduleCalculator(BusinessCalendar calendar) {
        this.calendar = calendar;
    }

    /** Jours d'envoi, triés par ordre croissant, sans doublon. */
    public List<LocalDate> sendDates(SchedulingRule rule) {
        TreeSet<LocalDate> dates = new TreeSet<>();
        boolean onlyBusiness = rule.businessDaysOnly();

        switch (rule.mode()) {
            case PONCTUEL -> dates.add(onlyBusiness
                    ? calendar.lastBusinessDayOnOrBefore(rule.sendDate())
                    : rule.sendDate());
            case AVANT_ECHEANCE -> {
                LocalDate due = rule.dueDate();
                addRange(dates, due.minusDays(rule.daysBefore()), due, onlyBusiness);
                if (onlyBusiness && !calendar.isBusinessDay(due)) {
                    dates.add(calendar.lastBusinessDayOnOrBefore(due));
                }
            }
            case PERIODE -> addRange(dates, rule.startDate(), rule.endDate(), onlyBusiness);
        }
        return List.copyOf(dates);
    }

    private void addRange(TreeSet<LocalDate> target, LocalDate firstDay, LocalDate lastDay, boolean onlyBusiness) {
        for (LocalDate day = firstDay; !day.isAfter(lastDay); day = day.plusDays(1)) {
            if (!onlyBusiness || calendar.isBusinessDay(day)) {
                target.add(day);
            }
        }
    }
}
