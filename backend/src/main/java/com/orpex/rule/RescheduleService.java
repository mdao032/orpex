package com.orpex.rule;

import com.orpex.core.calendar.BusinessCalendar;
import com.orpex.core.calendar.ScheduleCalculator;
import com.orpex.core.importing.RuleStatus;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Mise en accord des envois futurs d'une ligne activée avec sa configuration ({@link SendsDiff}) : après un changement
 * des jours fériés (décision Q10), ou d'une ligne (ré-import). Le passé n'est jamais touché.
 */
@Service
public class RescheduleService {

    private static final Logger log = LoggerFactory.getLogger(RescheduleService.class);

    public record Rescheduled(int rules, int cancelled, int added) {
    }

    private final RuleRepository rules;
    private final OccurrenceRepository occurrences;
    private final Clock clock;

    public RescheduleService(RuleRepository rules, OccurrenceRepository occurrences, Clock clock) {
        this.rules = rules;
        this.occurrences = occurrences;
        this.clock = clock;
    }

    /** À appeler dans la transaction qui modifie les jours fériés, avec le calendrier qui en résulte. */
    @Transactional(propagation = Propagation.MANDATORY)
    public Rescheduled afterHolidayChange(BusinessCalendar calendar, String reason) {
        ScheduleCalculator calculator = new ScheduleCalculator(calendar);
        Instant now = clock.instant();
        int changedRules = 0;
        int cancelled = 0;
        int added = 0;
        for (Rule rule : rules.findAllDependingOnHolidays()) {
            SendsDiff diff = diff(rule, calculator, now);
            if (!diff.isEmpty()) {
                apply(rule, diff, now);
                changedRules++;
                cancelled += diff.cancelled();
                added += diff.added();
                // Traçabilité en attendant le journal d'audit (phase 8).
                log.info("Recalcul ({}) de la relance {} (id {}, propriétaire {}) : {} envoi(s) annulé(s), {} ajouté(s)",
                        reason, rule.getBusinessId(), rule.getId(), rule.getOwnerId(), diff.cancelled(), diff.added());
            }
        }
        return new Rescheduled(changedRules, cancelled, added);
    }

    /** Écart pour la configuration actuelle de la ligne (rien n'est écrit). */
    public SendsDiff diff(Rule rule, ScheduleCalculator calculator, Instant now) {
        List<Occurrence> existing = occurrences.findAllByRuleIdOrderByScheduledDateAsc(rule.getId());
        if (rule.getStatus() == RuleStatus.CLOSED) {
            List<Occurrence> toCancel = existing.stream()
                    .filter(o -> OccurrenceState.NOT_STARTED.contains(o.getState())).toList();
            return new SendsDiff(toCancel, List.of(), List.of(), List.of());
        }
        OccurrenceState upcoming = rule.isDailyValidation()
                ? OccurrenceState.AWAITING_VALIDATION : OccurrenceState.PLANNED;
        return SendsDiff.compute(existing, calculator.sendDates(rule.scheduling()), day -> at(rule, day), upcoming, now);
    }

    /** Applique l'écart (dans la transaction de l'appelant). */
    public void apply(Rule rule, SendsDiff diff, Instant now) {
        OccurrenceState upcoming = rule.isDailyValidation()
                ? OccurrenceState.AWAITING_VALIDATION : OccurrenceState.PLANNED;
        diff.toCancel().forEach(occurrence -> occurrence.cancel(now));
        diff.toRevive().forEach(occurrence -> occurrence.revive(upcoming, now));
        diff.toMove().forEach(occurrence -> occurrence.moveTo(at(rule, occurrence.getScheduledDate()),
                SendsDiff.waitingState(occurrence.getState(), upcoming), now));
        for (LocalDate day : diff.toCreate()) {
            occurrences.save(new Occurrence(rule.getId(), day, at(rule, day), upcoming, rule.getOwnerId(), now));
        }
    }

    private Instant at(Rule rule, LocalDate day) {
        return day.atTime(rule.getSendTime()).atZone(clock.getZone()).toInstant();
    }
}
