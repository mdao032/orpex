package com.orpex.rule;

import com.orpex.core.calendar.ActivationPlanner;
import com.orpex.core.calendar.ScheduleCalculator;
import com.orpex.core.importing.RuleStatus;
import com.orpex.holiday.HolidayService;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Activation d'une ligne (spécification §6) : calcule la liste exacte de ses envois et la stocke ; le worker
 * n'évaluera jamais de règle au moment d'envoyer. Pas de rattrapage du passé ; un envoi du jour dont l'heure est
 * passée est enregistré « manqué ».
 */
@Service
public class ActivationService {

    public record ActivationResult(long ruleId, String businessId, int planned, int missed, LocalDate first,
                                   LocalDate last) {
    }

    /**
     * Ce que l'activation créerait maintenant : {@code planned} envois à venir du {@code first} au {@code last}, et
     * {@code missedToday} envoi du jour déjà manqué (heure passée).
     */
    public record Forecast(int planned, LocalDate first, LocalDate last, int missedToday) {
    }

    private final RuleRepository rules;
    private final OccurrenceRepository occurrences;
    private final HolidayService holidayService;
    private final Clock clock;

    public ActivationService(RuleRepository rules, OccurrenceRepository occurrences, HolidayService holidayService,
            Clock clock) {
        this.rules = rules;
        this.occurrences = occurrences;
        this.holidayService = holidayService;
        this.clock = clock;
    }

    @Transactional
    public ActivationResult activate(long ownerId, long ruleId) {
        Rule rule = rules.findForUpdateByIdAndOwnerId(ruleId, ownerId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Relance introuvable."));
        if (rule.isActivated()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "La relance " + rule.getBusinessId() + " est déjà activée.");
        }
        if (rule.getStatus() == RuleStatus.CLOSED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "La relance " + rule.getBusinessId() + " est close : elle n'a rien à envoyer.");
        }
        return createSends(rule, calculator(), clock.instant());
    }

    /** Active toutes les lignes non encore activées et non closes de l'utilisateur. */
    @Transactional
    public List<ActivationResult> activateAll(long ownerId) {
        ScheduleCalculator calculator = calculator();
        Instant now = clock.instant();
        List<ActivationResult> results = new ArrayList<>();
        for (Rule candidate : rules.findAllByOwnerIdAndActivatedAtIsNullOrderByBusinessIdAsc(ownerId)) {
            Rule rule = rules.findForUpdateByIdAndOwnerId(candidate.getId(), ownerId).orElseThrow();
            if (!rule.isActivated() && rule.getStatus() == RuleStatus.ACTIVE) {
                results.add(createSends(rule, calculator, now));
            }
        }
        return results;
    }

    /** Calculateur à jour des jours fériés, à réutiliser pour plusieurs lignes d'une même requête. */
    public ScheduleCalculator calculator() {
        return new ScheduleCalculator(holidayService.businessCalendar());
    }

    /** Envois qu'une activation créerait maintenant ; même calcul que l'activation elle-même. */
    public Forecast forecast(Rule rule, ScheduleCalculator calculator) {
        ActivationPlanner.Split split = split(rule, calculator, clock.instant());
        List<LocalDate> planned = split.planned();
        return new Forecast(planned.size(), planned.isEmpty() ? null : planned.getFirst(),
                planned.isEmpty() ? null : planned.getLast(), split.missed().size());
    }

    private ActivationPlanner.Split split(Rule rule, ScheduleCalculator calculator, Instant now) {
        return ActivationPlanner.split(calculator.sendDates(rule.scheduling()), rule.getSendTime(), clock.getZone(),
                now);
    }

    private ActivationResult createSends(Rule rule, ScheduleCalculator calculator, Instant now) {
        ActivationPlanner.Split split = split(rule, calculator, now);
        OccurrenceState upcoming = rule.isDailyValidation()
                ? OccurrenceState.AWAITING_VALIDATION : OccurrenceState.PLANNED;
        for (LocalDate day : split.planned()) {
            occurrences.save(new Occurrence(rule.getId(), day, at(rule, day), upcoming, rule.getOwnerId(), now));
        }
        for (LocalDate day : split.missed()) {
            occurrences.save(new Occurrence(rule.getId(), day, at(rule, day), OccurrenceState.MISSED,
                    rule.getOwnerId(), now));
        }
        rule.markActivated(now);
        List<LocalDate> planned = split.planned();
        return new ActivationResult(rule.getId(), rule.getBusinessId(), planned.size(), split.missed().size(),
                planned.isEmpty() ? null : planned.getFirst(), planned.isEmpty() ? null : planned.getLast());
    }

    private Instant at(Rule rule, LocalDate day) {
        return day.atTime(rule.getSendTime()).atZone(clock.getZone()).toInstant();
    }
}
