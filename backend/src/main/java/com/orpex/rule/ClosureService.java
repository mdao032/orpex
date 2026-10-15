package com.orpex.rule;

import com.orpex.audit.AuditEvent;
import com.orpex.audit.AuditLog;
import com.orpex.core.importing.RuleStatus;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * « Clore ce dossier » (spécification §6) : la ligne passe en statut clos et ses envois qui n'ont pas commencé à
 * partir sont annulés. Les envois passés (envoyés, manqués, en échec…) et un envoi en cours ne changent pas.
 */
@Service
public class ClosureService {

    public record ClosureResult(long ruleId, String businessId, int cancelled) {
    }

    private final RuleRepository rules;
    private final OccurrenceRepository occurrences;
    private final Clock clock;
    private final AuditLog audit;

    public ClosureService(RuleRepository rules, OccurrenceRepository occurrences, Clock clock, AuditLog audit) {
        this.rules = rules;
        this.occurrences = occurrences;
        this.clock = clock;
        this.audit = audit;
    }

    @Transactional
    public ClosureResult close(long ownerId, long ruleId) {
        Rule rule = rules.findForUpdateByIdAndOwnerId(ruleId, ownerId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Relance introuvable."));
        if (rule.getStatus() == RuleStatus.CLOSED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Le dossier " + rule.getBusinessId() + " est déjà clos.");
        }
        Instant now = clock.instant();
        List<Occurrence> upcoming = occurrences.findAllByRuleIdAndStateIn(rule.getId(), OccurrenceState.NOT_STARTED);
        upcoming.forEach(occurrence -> occurrence.cancel(now));
        rule.close(now);
        audit.record(AuditEvent.RULE_CLOSED, ownerId, "RULE", rule.getBusinessId(), null,
                upcoming.size() + " envoi(s) annulé(s)");
        return new ClosureResult(rule.getId(), rule.getBusinessId(), upcoming.size());
    }
}
