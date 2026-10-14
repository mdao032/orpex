package com.orpex.rule;

import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private static final Logger log = LoggerFactory.getLogger(ToCheckService.class);

    private final RuleRepository rules;
    private final OccurrenceRepository occurrences;
    private final Clock clock;

    public ToCheckService(RuleRepository rules, OccurrenceRepository occurrences, Clock clock) {
        this.rules = rules;
        this.occurrences = occurrences;
        this.clock = clock;
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
        // Traçabilité en attendant le journal d'audit (phase 8).
        log.info("Envoi {} de la relance {} (id {}) déclaré {} par son propriétaire {}", occurrenceId,
                rule.getBusinessId(), rule.getId(), sent ? "parti" : "non parti", ownerId);
        return occurrence;
    }
}
