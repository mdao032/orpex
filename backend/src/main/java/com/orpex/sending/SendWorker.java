package com.orpex.sending;

import com.orpex.config.OrpexProperties;
import com.orpex.core.mail.MailGateway;
import com.orpex.core.mail.MailMessage;
import com.orpex.core.mail.MailSendException;
import com.orpex.core.mail.MessageTemplate;
import com.orpex.rule.Occurrence;
import com.orpex.rule.OccurrenceRepository;
import com.orpex.rule.OccurrenceState;
import com.orpex.rule.Rule;
import com.orpex.rule.RuleRepository;
import com.orpex.rule.SendingControlService;
import java.time.Clock;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Worker d'envoi (spécification §10) : prend les envois dus et les expédie. Il n'évalue jamais de règle : il envoie
 * ce que l'activation a planifié.
 *
 * <ol>
 *   <li>Rien pendant une pause générale.</li>
 *   <li>Un envoi dû depuis plus de {@code orpex.worker.max-lateness} n'est plus fait : manqué.</li>
 *   <li>Réservation en courte transaction ({@code FOR UPDATE SKIP LOCKED}) : passage en {@code SENDING}.</li>
 *   <li>Envoi hors transaction, puis {@code SENT} ou {@code FAILED} (une transaction par envoi).</li>
 * </ol>
 * Un arrêt entre l'acceptation par le serveur et l'écriture de {@code SENT} laisse l'envoi en {@code SENDING} : il
 * sera marqué « à vérifier » au redémarrage, jamais renvoyé automatiquement.
 */
@Service
public class SendWorker {

    private static final Logger log = LoggerFactory.getLogger(SendWorker.class);
    private static final EnumSet<OccurrenceState> SENDABLE = EnumSet.of(OccurrenceState.PLANNED,
            OccurrenceState.VALIDATED);

    /** Bilan d'un passage du worker. */
    public record Pass(int sent, int failed, int missed) {
    }

    private record Reservation(List<Occurrence> due, int missed) {
    }

    private final OccurrenceRepository occurrences;
    private final RuleRepository rules;
    private final SendingControlService sendingControl;
    private final MailGateway gateway;
    private final TransactionTemplate transaction;
    private final OrpexProperties properties;
    private final Clock clock;

    public SendWorker(OccurrenceRepository occurrences, RuleRepository rules, SendingControlService sendingControl,
            MailGateway gateway, TransactionTemplate transaction, OrpexProperties properties, Clock clock) {
        this.occurrences = occurrences;
        this.rules = rules;
        this.sendingControl = sendingControl;
        this.gateway = gateway;
        this.transaction = transaction;
        this.properties = properties;
        this.clock = clock;
    }

    /** Un passage : traite au plus un lot d'envois dus. */
    public Pass processDueSends() {
        if (sendingControl.globalState().paused()) {
            return new Pass(0, 0, 0);
        }
        Instant now = clock.instant();
        Instant oldest = now.minus(properties.worker().maxLateness());
        Reservation reservation = transaction.execute(status -> {
            int missed = occurrences.markTooLateAsMissed(now, oldest, SENDABLE, OccurrenceState.MISSED);
            if (missed > 0) {
                log.warn("{} envoi(s) dû(s) depuis plus de {} : marqués manqués, non envoyés", missed,
                        properties.worker().maxLateness());
            }
            List<Long> ids = occurrences.lockDue(now, oldest, properties.worker().batchSize());
            List<Occurrence> due = occurrences.findAllByIdIn(ids);
            due.forEach(occurrence -> occurrence.markSending(now));
            return new Reservation(due, missed);
        });
        int sent = 0;
        int failed = 0;
        for (Occurrence occurrence : reservation.due()) {
            if (send(occurrence)) {
                sent++;
            } else {
                failed++;
            }
        }
        return new Pass(sent, failed, reservation.missed());
    }

    /** Envoie un envoi réservé ; renvoie vrai s'il est parti. */
    private boolean send(Occurrence reserved) {
        Rule rule = rules.findForSending(reserved.getRuleId()).orElseThrow();
        String error;
        try {
            Map<String, String> values = rule.variableValues();
            gateway.send(new MailMessage(properties.mail().from(), rule.getRecipientEmail(),
                    MessageTemplate.render(rule.getSubject(), values), MessageTemplate.render(rule.getBody(), values)));
            record(reserved.getId(), occurrence -> occurrence.markSent(clock.instant()));
            log.info("Envoi {} parti : relance {} (id {}) du {}", reserved.getId(), rule.getBusinessId(),
                    rule.getId(), reserved.getScheduledDate());
            return true;
        } catch (MessageTemplate.MissingVariableException e) {
            error = "message incomplet : " + e.getMessage();
        } catch (MailSendException e) {
            error = e.getMessage();
        }
        String reason = error;
        record(reserved.getId(), occurrence -> occurrence.markFailed(reason, clock.instant()));
        log.warn("Envoi {} en échec : relance {} (id {}) du {} : {}", reserved.getId(), rule.getBusinessId(),
                rule.getId(), reserved.getScheduledDate(), reason);
        return false;
    }

    private void record(long occurrenceId, Consumer<Occurrence> change) {
        transaction.executeWithoutResult(status -> change.accept(occurrences.findById(occurrenceId).orElseThrow()));
    }
}
