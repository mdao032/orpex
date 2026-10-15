package com.orpex.rule;

import com.orpex.audit.AuditEvent;
import com.orpex.audit.AuditLog;
import java.time.Clock;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Décision de l'utilisateur sur un envoi « à vérifier » (spécification §10) : après un arrêt pendant l'envoi, seul
 * l'utilisateur sait (messagerie, destinataire) si le mail est parti. Rien n'est jamais renvoyé automatiquement.
 */
@Service
public class ToCheckService {

    private final RuleRepository rules;
    private final OccurrenceRepository occurrences;
    private final Clock clock;
    private final AuditLog audit;

    public ToCheckService(RuleRepository rules, OccurrenceRepository occurrences, Clock clock, AuditLog audit) {
        this.rules = rules;
        this.occurrences = occurrences;
        this.clock = clock;
        this.audit = audit;
    }

    @Transactional
    public Occurrence resolve(long ownerId, long ruleId, long occurrenceId, boolean sent) {
        Rule rule = rules.findByIdAndOwnerId(ruleId, ownerId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Relance introuvable."));
        Occurrence occurrence = occurrences.findById(occurrenceId)
                .filter(o -> o.getRuleId().equals(rule.getId()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Envoi introuvable."));
        if (occurrence.getState() != OccurrenceState.TO_CHECK) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Cet envoi n'est pas à vérifier.");
        }
        occurrence.resolveToCheck(sent, clock.instant());
        audit.record(AuditEvent.SEND_RESOLVED, ownerId, "OCCURRENCE", occurrenceId, rule.getBusinessId(),
                sent ? "déclaré parti" : "déclaré non parti");
        return occurrence;
    }
}
