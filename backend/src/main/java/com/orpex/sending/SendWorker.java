package com.orpex.sending;

import com.orpex.config.OrpexProperties;
import com.orpex.core.mail.MailGateway;
import com.orpex.core.mail.MailMessage;
import com.orpex.core.mail.MailSendException;
import com.orpex.core.mail.MessageTemplate;
import com.orpex.mail.MailSenderProvider;
import com.orpex.rule.Occurrence;
import com.orpex.rule.OccurrenceRepository;
import com.orpex.rule.OccurrenceState;
import com.orpex.rule.Rule;
import com.orpex.rule.RuleRepository;
import com.orpex.rule.SendingControlService;
import java.time.Clock;
import java.time.Duration;
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
 *   <li>Envoi hors transaction, puis {@code SENT} ; après un échec temporaire, nouvelle tentative programmée
 *       ({@code orpex.worker.retry-delays}, 3 au plus) ; sinon, ou une fois les tentatives épuisées,
 *       {@code FAILED}.</li>
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
    public record Pass(int sent, int retried, int failed, int missed) {
    }

    private record Reservation(List<Occurrence> due, int missed) {
    }

    private final OccurrenceRepository occurrences;
    private final RuleRepository rules;
    private final SendingControlService sendingControl;
    private final MailGateway gateway;
    private final MailSenderProvider senders;
    private final TransactionTemplate transaction;
    private final OrpexProperties properties;
    private final Clock clock;

    public SendWorker(OccurrenceRepository occurrences, RuleRepository rules, SendingControlService sendingControl,
            MailGateway gateway, MailSenderProvider senders, TransactionTemplate transaction,
            OrpexProperties properties, Clock clock) {
        this.occurrences = occurrences;
        this.rules = rules;
        this.sendingControl = sendingControl;
        this.gateway = gateway;
        this.senders = senders;
        this.transaction = transaction;
        this.properties = properties;
        this.clock = clock;
    }

    static final String INTERRUPTED = "envoi interrompu (arrêt de l'application) : vérifiez s'il est parti avant de "
            + "décider ; il ne sera jamais renvoyé automatiquement";

    /**
     * Envois restés « en cours » au-delà de {@code orpex.worker.sending-timeout} : à vérifier par l'utilisateur.
     * Appelé au démarrage et à chaque passage (ce qui couvre aussi l'arrêt d'une autre instance).
     */
    public int recoverInterruptedSends() {
        Instant now = clock.instant();
        Integer marked = transaction.execute(status -> occurrences.markInterruptedAsToCheck(
                now.minus(properties.worker().sendingTimeout()), now, INTERRUPTED, OccurrenceState.SENDING,
                OccurrenceState.TO_CHECK));
        if (marked != null && marked > 0) {
            log.warn("{} envoi(s) interrompu(s) par un arrêt pendant l'envoi : marqués à vérifier", marked);
        }
        return marked == null ? 0 : marked;
    }

    /** Un passage : traite au plus un lot d'envois dus. */
    public Pass processDueSends() {
        recoverInterruptedSends();
        if (sendingControl.globalState().paused()) {
            return new Pass(0, 0, 0, 0);
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
        int retried = 0;
        int failed = 0;
        for (Occurrence occurrence : reservation.due()) {
            switch (send(occurrence)) {
                case SENT -> sent++;
                case RETRY -> retried++;
                case FAILED -> failed++;
            }
        }
        return new Pass(sent, retried, failed, reservation.missed());
    }

    private enum Outcome { SENT, RETRY, FAILED }

    /** Envoie un envoi réservé et enregistre le résultat. */
    private Outcome send(Occurrence reserved) {
        Rule rule = rules.findForSending(reserved.getRuleId()).orElseThrow();
        String error;
        try {
            Map<String, String> values = rule.variableValues();
            gateway.send(new MailMessage(senders.current().from(), rule.getRecipientEmail(),
                    MessageTemplate.render(rule.getSubject(), values), MessageTemplate.render(rule.getBody(), values)));
            record(reserved.getId(), occurrence -> occurrence.markSent(clock.instant()));
            log.info("Envoi {} parti : relance {} (id {}) du {}", reserved.getId(), rule.getBusinessId(),
                    rule.getId(), reserved.getScheduledDate());
            return Outcome.SENT;
        } catch (MessageTemplate.MissingVariableException e) {
            error = "message incomplet : " + e.getMessage();
        } catch (MailSendException e) {
            List<Duration> delays = properties.worker().retryDelays();
            int failures = reserved.getAttempts() + 1;
            if (e.isRetryable() && failures <= delays.size()) {
                Instant now = clock.instant();
                Instant next = now.plus(delays.get(failures - 1));
                OccurrenceState waiting = rule.isDailyValidation() ? OccurrenceState.VALIDATED : OccurrenceState.PLANNED;
                record(reserved.getId(), occurrence -> occurrence.scheduleRetry(e.getMessage(), next, waiting, now));
                log.warn("Envoi {} : échec temporaire {} ({}), nouvelle tentative à {}", reserved.getId(), failures,
                        e.getMessage(), next);
                return Outcome.RETRY;
            }
            error = e.isRetryable() ? e.getMessage() + " (" + failures + " tentatives)" : e.getMessage();
        }
        String reason = error;
        record(reserved.getId(), occurrence -> occurrence.markFailed(reason, clock.instant()));
        log.warn("Envoi {} en échec : relance {} (id {}) du {} : {}", reserved.getId(), rule.getBusinessId(),
                rule.getId(), reserved.getScheduledDate(), reason);
        return Outcome.FAILED;
    }

    private void record(long occurrenceId, Consumer<Occurrence> change) {
        transaction.executeWithoutResult(status -> change.accept(occurrences.findById(occurrenceId).orElseThrow()));
    }
}
