package com.orpex.rule;

import com.orpex.core.calendar.BusinessCalendar;
import com.orpex.core.calendar.ScheduleCalculator;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Recalcul des envois futurs après un changement des jours fériés (décision Q10 : automatique). Pour chaque ligne
 * activée, non close, en jours ouvrés uniquement :
 * <ul>
 *   <li>un envoi non commencé, encore à venir, sur un jour qui n'est plus un jour d'envoi → annulé ;</li>
 *   <li>un jour d'envoi à venir sans envoi → envoi créé, ou envoi annulé de ce jour repris ;</li>
 *   <li>rien dans le passé : envoyé, manqué, en échec, ou envoi du jour dont l'heure est passée, ne change pas.</li>
 * </ul>
 * Le report d'une échéance non ouvrée au jour ouvré précédent découle du même calcul qu'à l'activation.
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
            Set<LocalDate> wanted = calculator.sendDates(rule.scheduling()).stream()
                    .filter(day -> at(rule, day).isAfter(now))
                    .collect(Collectors.toSet());
            Map<LocalDate, Occurrence> existing = occurrences.findAllByRuleIdOrderByScheduledDateAsc(rule.getId())
                    .stream().collect(Collectors.toMap(Occurrence::getScheduledDate, Function.identity()));
            OccurrenceState upcoming = rule.isDailyValidation()
                    ? OccurrenceState.AWAITING_VALIDATION : OccurrenceState.PLANNED;

            int ruleCancelled = 0;
            int ruleAdded = 0;
            for (Occurrence occurrence : existing.values()) {
                boolean stillToCome = occurrence.getScheduledAt().isAfter(now);
                if (stillToCome && OccurrenceState.NOT_STARTED.contains(occurrence.getState())
                        && !wanted.contains(occurrence.getScheduledDate())) {
                    occurrence.cancel(now);
                    ruleCancelled++;
                }
            }
            for (LocalDate day : wanted) {
                Occurrence occurrence = existing.get(day);
                if (occurrence == null) {
                    occurrences.save(new Occurrence(rule.getId(), day, at(rule, day), upcoming, rule.getOwnerId(),
                            now));
                    ruleAdded++;
                } else if (occurrence.getState() == OccurrenceState.CANCELLED) {
                    occurrence.revive(upcoming, now);
                    ruleAdded++;
                }
            }
            if (ruleCancelled + ruleAdded > 0) {
                changedRules++;
                cancelled += ruleCancelled;
                added += ruleAdded;
                // Traçabilité en attendant le journal d'audit (phase 8).
                log.info("Recalcul ({}) de la relance {} (id {}, propriétaire {}) : {} envoi(s) annulé(s), {} ajouté(s)",
                        reason, rule.getBusinessId(), rule.getId(), rule.getOwnerId(), ruleCancelled, ruleAdded);
            }
        }
        return new Rescheduled(changedRules, cancelled, added);
    }

    private Instant at(Rule rule, LocalDate day) {
        return day.atTime(rule.getSendTime()).atZone(clock.getZone()).toInstant();
    }
}
