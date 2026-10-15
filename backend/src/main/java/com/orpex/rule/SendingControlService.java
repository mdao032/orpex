package com.orpex.rule;

import com.orpex.audit.AuditEvent;
import com.orpex.audit.AuditLog;
import com.orpex.core.importing.RuleStatus;
import java.time.Clock;
import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Pauses (spécification §11) : par ligne, par son propriétaire ; générale, par un administrateur. Une pause suspend les
 * envois à venir sans les supprimer. À la reprise, un envoi dont l'heure est passée pendant la pause devient
 * « manqué » : il ne part jamais en retard. Le worker d'envoi doit ignorer les lignes en pause et la pause générale.
 */
@Service
public class SendingControlService {

    public record PauseState(boolean paused, Instant changedAt) {
    }

    public record RulePauseResult(long ruleId, String businessId, boolean paused, int missed) {
    }

    private final SendingControlRepository control;
    private final RuleRepository rules;
    private final OccurrenceRepository occurrences;
    private final Clock clock;
    private final AuditLog audit;

    public SendingControlService(SendingControlRepository control, RuleRepository rules,
            OccurrenceRepository occurrences, Clock clock, AuditLog audit) {
        this.control = control;
        this.rules = rules;
        this.occurrences = occurrences;
        this.clock = clock;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public PauseState globalState() {
        SendingControl state = control.findById(SendingControl.SINGLE_ROW).orElseThrow();
        return new PauseState(state.isPaused(), state.getChangedAt());
    }

    /** Pause ou reprise générale ; à la reprise, renvoie le nombre d'envois devenus manqués. */
    @Transactional
    public int setGlobalPause(boolean paused, long adminId) {
        SendingControl state = control.findForUpdateById(SendingControl.SINGLE_ROW).orElseThrow();
        if (state.isPaused() == paused) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    paused ? "Les envois sont déjà en pause." : "Les envois ne sont pas en pause.");
        }
        Instant now = clock.instant();
        state.change(paused, adminId, now);
        int missed = paused ? 0
                : occurrences.markAllMissedBefore(now, OccurrenceState.NOT_STARTED, OccurrenceState.MISSED);
        audit.record(paused ? AuditEvent.SENDING_PAUSED : AuditEvent.SENDING_RESUMED,
                paused ? null : missed + " envoi(s) manqué(s) pendant la pause");
        return missed;
    }

    @Transactional
    public RulePauseResult setRulePause(long ownerId, long ruleId, boolean paused) {
        Rule rule = rules.findForUpdateByIdAndOwnerId(ruleId, ownerId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Relance introuvable."));
        if (rule.getStatus() == RuleStatus.CLOSED || !rule.isActivated()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Seule une relance activée et non close peut être mise en pause.");
        }
        if (rule.isPaused() == paused) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "La relance " + rule.getBusinessId()
                    + (paused ? " est déjà en pause." : " n'est pas en pause."));
        }
        Instant now = clock.instant();
        rule.pause(paused, now);
        int missed = paused ? 0
                : occurrences.markMissedBefore(rule.getId(), now, OccurrenceState.NOT_STARTED, OccurrenceState.MISSED);
        audit.record(paused ? AuditEvent.RULE_PAUSED : AuditEvent.RULE_RESUMED, ownerId, "RULE",
                rule.getBusinessId(), null, paused ? null : missed + " envoi(s) manqué(s) pendant la pause");
        return new RulePauseResult(rule.getId(), rule.getBusinessId(), paused, missed);
    }
}
