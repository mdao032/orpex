package com.orpex.validation;

import com.orpex.audit.AuditEvent;
import com.orpex.audit.AuditLog;
import com.orpex.account.UserAccount;
import com.orpex.account.UserAccountRepository;
import com.orpex.config.OrpexProperties;
import com.orpex.core.mail.MailGateway;
import com.orpex.core.mail.MailMessage;
import com.orpex.core.mail.MailSendException;
import com.orpex.mail.MailSenderProvider;
import com.orpex.rule.Occurrence;
import com.orpex.rule.OccurrenceRepository;
import com.orpex.rule.OccurrenceState;
import com.orpex.validation.ValidationService.DayDecision;
import com.orpex.validation.ValidationService.Pending;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Récapitulatif de validation (spécification §7) : avant l'heure limite, le propriétaire reçoit un mail listant ses
 * relances du jour qui attendent sa validation. Déclenché quand une d'elles arrive à
 * {@code orpex.validation-reminder-lead} de son heure limite ; chaque envoi n'est rappelé qu'une fois.
 */
@Service
public class ValidationReminderService {

    private static final Logger log = LoggerFactory.getLogger(ValidationReminderService.class);
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");

    private final OccurrenceRepository occurrences;
    private final ValidationService validation;
    private final UserAccountRepository accounts;
    private final MailGateway gateway;
    private final MailSenderProvider senders;
    private final TransactionTemplate transaction;
    private final OrpexProperties properties;
    private final Clock clock;
    private final AuditLog audit;

    public ValidationReminderService(OccurrenceRepository occurrences, ValidationService validation,
            UserAccountRepository accounts, MailGateway gateway, MailSenderProvider senders,
            TransactionTemplate transaction, OrpexProperties properties, Clock clock, AuditLog audit) {
        this.occurrences = occurrences;
        this.validation = validation;
        this.accounts = accounts;
        this.gateway = gateway;
        this.senders = senders;
        this.transaction = transaction;
        this.properties = properties;
        this.clock = clock;
        this.audit = audit;
    }

    /** Envoie les récapitulatifs dus ; renvoie le nombre de mails partis. */
    public int sendDueReminders() {
        Instant now = clock.instant();
        Duration cutoff = Duration.ofMinutes(properties.validationCutoffMinutes());
        Duration lead = properties.validationReminderLead();
        List<Occurrence> notReminded = occurrences.findAwaitingNotReminded(LocalDate.now(clock),
                OccurrenceState.AWAITING_VALIDATION);
        Map<Long, List<Occurrence>> byOwner = notReminded.stream()
                .collect(Collectors.groupingBy(Occurrence::getCreatedBy));
        int sent = 0;
        for (var entry : byOwner.entrySet()) {
            boolean due = entry.getValue().stream().anyMatch(o -> {
                Instant cutoffAt = o.getScheduledAt().minus(cutoff);
                return !now.isBefore(cutoffAt.minus(lead)) && now.isBefore(cutoffAt);
            });
            if (due && remind(entry.getKey())) {
                sent++;
            }
        }
        return sent;
    }

    /** Un mail au propriétaire listant toutes ses relances du jour encore à valider. */
    private boolean remind(long ownerId) {
        List<Pending> pending = validation.today(ownerId).stream()
                .filter(p -> p.open() && p.decision() == DayDecision.PENDING)
                .toList();
        UserAccount owner = accounts.findById(ownerId).orElse(null);
        if (pending.isEmpty() || owner == null || !owner.isEnabled()) {
            return false;
        }
        try {
            gateway.send(new MailMessage(senders.current().from(), owner.getEmail(), subject(pending.size()),
                    body(pending)));
        } catch (MailSendException e) {
            log.warn("Récapitulatif de validation non envoyé à {} (nouvel essai au passage suivant) : {}",
                    owner.getEmail(), e.getMessage());
            return false;
        }
        Set<Long> ids = pending.stream().map(Pending::sendId).collect(Collectors.toSet());
        Instant now = clock.instant();
        transaction.executeWithoutResult(status -> {
            occurrences.markReminded(ids, now);
            audit.record(AuditEvent.VALIDATION_REMINDER_SENT, ownerId, "USER", owner.getEmail(), null,
                    pending.size() + " relance(s) à valider");
        });
        log.info("Récapitulatif de validation envoyé à {} : {} relance(s)", owner.getEmail(), pending.size());
        return true;
    }

    private static String subject(int count) {
        return "ORPEX : " + count + " relance(s) à valider aujourd'hui";
    }

    private String body(List<Pending> pending) {
        StringBuilder text = new StringBuilder("Bonjour,\n\nCes relances ne partiront que si vous les validez avant "
                + "leur heure limite :\n\n");
        for (Pending p : pending) {
            text.append("- ").append(p.businessId());
            if (p.caseReference() != null) {
                text.append(" (").append(p.caseReference()).append(')');
            }
            text.append(" : ").append(p.subject()).append(" → ").append(p.recipientEmail())
                    .append("\n  envoi à ").append(time(p.scheduledAt()))
                    .append(", à valider avant ").append(time(p.cutoffAt())).append('\n');
        }
        text.append("\nValider ou décocher : ").append(properties.appUrl().replaceAll("/+$", ""))
                .append("/validation\n\nSans validation, ces relances ne partiront pas.");
        return text.toString();
    }

    private String time(Instant instant) {
        return TIME.format(instant.atZone(clock.getZone()));
    }
}
