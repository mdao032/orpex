package com.orpex.validation;

import com.orpex.config.OrpexProperties;
import com.orpex.rule.Occurrence;
import com.orpex.rule.OccurrenceRepository;
import com.orpex.rule.OccurrenceState;
import com.orpex.rule.Rule;
import com.orpex.rule.RuleRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Validation quotidienne (spécification §7) : avec {@code validation_quotidienne = oui}, rien ne part sans que le
 * propriétaire l'ait confirmé le jour même, avant l'heure limite ({@code orpex.validation-cutoff-minutes} avant
 * l'heure d'envoi). Ce qu'il décoche ne part pas. Un envoi non validé à son heure ne part jamais (worker).
 */
@Service
public class ValidationService {

    private static final Logger log = LoggerFactory.getLogger(ValidationService.class);

    /** Décision du jour sur un envoi. */
    public enum DayDecision { PENDING, VALIDATED, UNCHECKED }

    /**
     * Une relance du jour concernée par la validation ; {@code open} : avant l'heure limite (validation, ou retour sur
     * la décision, encore possibles).
     */
    public record Pending(long sendId, long ruleId, String businessId, String caseReference, String recipientEmail,
                          String subject, Instant scheduledAt, Instant cutoffAt, boolean open, long alreadySent,
                          DayDecision decision) {
    }

    /** Envois refusés : introuvables, déjà traités, pas d'aujourd'hui, ou heure limite passée. */
    public record Refusal(long sendId, String reason) {
    }

    public record Decision(int validated, int rejected, List<Refusal> refused) {
    }

    private final OccurrenceRepository occurrences;
    private final RuleRepository rules;
    private final Clock clock;
    private final Duration cutoff;

    public ValidationService(OccurrenceRepository occurrences, RuleRepository rules, OrpexProperties properties,
            Clock clock) {
        this.occurrences = occurrences;
        this.rules = rules;
        this.clock = clock;
        this.cutoff = Duration.ofMinutes(properties.validationCutoffMinutes());
    }

    @Transactional(readOnly = true)
    public List<Pending> today(long ownerId) {
        Instant now = clock.instant();
        List<Occurrence> awaiting = occurrences.findForDailyValidation(ownerId, LocalDate.now(clock));
        Set<Long> ruleIds = awaiting.stream().map(Occurrence::getRuleId).collect(Collectors.toSet());
        Map<Long, Rule> byId = rules.findAllByOwnerIdOrderByBusinessIdAsc(ownerId).stream()
                .filter(rule -> ruleIds.contains(rule.getId()))
                .collect(Collectors.toMap(Rule::getId, Function.identity()));
        Map<Long, Long> sent = sentCounts(ruleIds);
        return awaiting.stream().map(o -> {
            Rule rule = byId.get(o.getRuleId());
            Instant cutoffAt = o.getScheduledAt().minus(cutoff);
            DayDecision decision = switch (o.getState()) {
                case VALIDATED -> DayDecision.VALIDATED;
                case NOT_VALIDATED -> DayDecision.UNCHECKED;
                default -> DayDecision.PENDING;
            };
            return new Pending(o.getId(), rule.getId(), rule.getBusinessId(), rule.getCaseReference(),
                    rule.getRecipientEmail(), rule.getSubject(), o.getScheduledAt(), cutoffAt, now.isBefore(cutoffAt),
                    sent.getOrDefault(rule.getId(), 0L), decision);
        }).toList();
    }

    /** Valide {@code validate} et décoche {@code reject} ; chaque envoi refusé est expliqué, les autres s'appliquent. */
    @Transactional
    public Decision decide(long ownerId, Collection<Long> validate, Collection<Long> reject) {
        Instant now = clock.instant();
        LocalDate today = LocalDate.now(clock);
        List<Refusal> refused = new ArrayList<>();
        List<String> validatedIds = new ArrayList<>();
        List<String> rejectedIds = new ArrayList<>();
        Set<Long> all = new LinkedHashSet<>(validate);
        all.addAll(reject);
        for (Long sendId : all) {
            boolean keep = validate.contains(sendId);
            Occurrence occurrence = occurrences.findById(sendId).orElse(null);
            Rule rule = occurrence == null ? null
                    : rules.findByIdAndOwnerId(occurrence.getRuleId(), ownerId).orElse(null);
            String reason = refusalReason(occurrence, rule, today, now);
            if (reason != null) {
                refused.add(new Refusal(sendId, reason));
                continue;
            }
            if (keep) {
                occurrence.validate(ownerId, now);
                validatedIds.add(rule.getBusinessId());
            } else {
                occurrence.reject(ownerId, now);
                rejectedIds.add(rule.getBusinessId());
            }
        }
        // Traçabilité (spécification §7) : qui, quand, ce qui a été décoché ; journal d'audit à la phase 8.
        log.info("Validation du jour par l'utilisateur {} : validées {}, décochées {}, refusées {}", ownerId,
                validatedIds, rejectedIds, refused.size());
        return new Decision(validatedIds.size(), rejectedIds.size(), refused);
    }

    /**
     * Revenir sur sa décision du jour (validé ou décoché) : l'envoi attend de nouveau sa validation. Seulement le jour
     * même et avant l'heure limite ; un envoi devenu « non validé » faute de validation reste définitif.
     */
    @Transactional
    public Pending reopen(long ownerId, long sendId) {
        Instant now = clock.instant();
        Occurrence occurrence = occurrences.findById(sendId).orElse(null);
        Rule rule = occurrence == null ? null : rules.findByIdAndOwnerId(occurrence.getRuleId(), ownerId).orElse(null);
        if (occurrence == null || rule == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Envoi introuvable.");
        }
        if (!occurrence.isDecidedByOwner()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Aucune décision à reprendre sur cet envoi.");
        }
        if (!occurrence.getScheduledDate().equals(LocalDate.now(clock))
                || !now.isBefore(occurrence.getScheduledAt().minus(cutoff))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Trop tard : l'heure limite de validation de cet envoi est passée.");
        }
        String before = occurrence.getState() == OccurrenceState.VALIDATED ? "validé" : "décoché";
        occurrence.reopen(now);
        log.info("Validation du jour : l'utilisateur {} revient sur sa décision ({}) pour l'envoi {} de la relance {}",
                ownerId, before, sendId, rule.getBusinessId());
        return today(ownerId).stream().filter(p -> p.sendId() == sendId).findFirst().orElseThrow();
    }

    private String refusalReason(Occurrence occurrence, Rule rule, LocalDate today, Instant now) {
        if (occurrence == null || rule == null) {
            return "Envoi introuvable.";
        }
        if (occurrence.getState() != OccurrenceState.AWAITING_VALIDATION) {
            return "Cet envoi n'attend plus de validation.";
        }
        if (!occurrence.getScheduledDate().equals(today)) {
            return "Seuls les envois d'aujourd'hui se valident, le jour même.";
        }
        if (!now.isBefore(occurrence.getScheduledAt().minus(cutoff))) {
            return "L'heure limite de validation est passée : cet envoi ne partira pas.";
        }
        return null;
    }

    private Map<Long, Long> sentCounts(Set<Long> ruleIds) {
        Map<Long, Long> counts = new HashMap<>();
        if (!ruleIds.isEmpty()) {
            for (Object[] row : occurrences.countByRuleIdsAndState(ruleIds, OccurrenceState.SENT)) {
                counts.put((Long) row[0], (Long) row[1]);
            }
        }
        return counts;
    }
}
