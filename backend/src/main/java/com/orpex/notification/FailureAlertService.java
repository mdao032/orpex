package com.orpex.notification;

import com.orpex.account.Role;
import com.orpex.account.UserAccount;
import com.orpex.account.UserAccountRepository;
import com.orpex.audit.AuditEvent;
import com.orpex.audit.AuditLog;
import com.orpex.config.OrpexProperties;
import com.orpex.core.mail.MailGateway;
import com.orpex.core.mail.MailMessage;
import com.orpex.core.mail.MailSendException;
import com.orpex.mail.MailSenderProvider;
import com.orpex.rule.Occurrence;
import com.orpex.rule.OccurrenceRepository;
import com.orpex.rule.OccurrenceState;
import com.orpex.rule.Rule;
import com.orpex.rule.RuleRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Alerte d'échec (spécification §11) : dès qu'un envoi passe en échec ou à vérifier, un mail à son propriétaire, et
 * un mail récapitulatif aux administrateurs. Chaque envoi n'est signalé qu'une fois ; si le mail au propriétaire est
 * refusé par le relais, l'alerte est retentée au passage suivant.
 */
@Service
public class FailureAlertService {

    private static final Logger log = LoggerFactory.getLogger(FailureAlertService.class);
    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("dd/MM/yyyy à HH:mm");

    private final OccurrenceRepository occurrences;
    private final RuleRepository rules;
    private final UserAccountRepository accounts;
    private final MailGateway gateway;
    private final MailSenderProvider senders;
    private final TransactionTemplate transaction;
    private final OrpexProperties properties;
    private final Clock clock;
    private final AuditLog audit;

    public FailureAlertService(OccurrenceRepository occurrences, RuleRepository rules,
            UserAccountRepository accounts, MailGateway gateway, MailSenderProvider senders,
            TransactionTemplate transaction, OrpexProperties properties, Clock clock, AuditLog audit) {
        this.occurrences = occurrences;
        this.rules = rules;
        this.accounts = accounts;
        this.gateway = gateway;
        this.senders = senders;
        this.transaction = transaction;
        this.properties = properties;
        this.clock = clock;
        this.audit = audit;
    }

    /** Signale les nouveaux échecs ; renvoie le nombre d'envois signalés. */
    public int sendDueAlerts() {
        Map<Long, List<Occurrence>> byOwner = occurrences.findNotAlertedProblems().stream()
                .collect(Collectors.groupingBy(Occurrence::getCreatedBy));
        List<String> forAdmins = new ArrayList<>();
        int alerted = 0;
        for (var entry : byOwner.entrySet()) {
            long ownerId = entry.getKey();
            UserAccount owner = accounts.findById(ownerId).orElse(null);
            List<String> lines = entry.getValue().stream().map(o -> line(o, ownerId)).toList();
            if (owner != null && owner.isEnabled()) {
                String subject = "ORPEX : " + lines.size() + " envoi(s) en échec ou à vérifier";
                try {
                    gateway.send(new MailMessage(senders.current().from(), owner.getEmail(), subject,
                            "Bonjour,\n\nCes envois ne sont pas partis normalement :\n\n" + String.join("\n", lines)
                                    + "\n\nVoir le détail et quoi faire : " + url() + "/lignes"));
                } catch (MailSendException e) {
                    log.warn("Alerte d'échec non envoyée à {} (nouvel essai au passage suivant) : {}",
                            owner.getEmail(), e.getMessage());
                    continue;
                }
            }
            List<Long> ids = entry.getValue().stream().map(Occurrence::getId).toList();
            Instant now = clock.instant();
            String recipient = owner == null ? null : owner.getEmail();
            transaction.executeWithoutResult(status -> {
                occurrences.markAlerted(ids, now);
                audit.record(AuditEvent.FAILURE_ALERT_SENT, ownerId, "USER", recipient, null,
                        lines.size() + " envoi(s) signalé(s)");
            });
            lines.forEach(line -> forAdmins.add(line + " (propriétaire : " + recipient + ")"));
            alerted += ids.size();
        }
        if (!forAdmins.isEmpty()) {
            alertAdmins(forAdmins);
        }
        return alerted;
    }

    private void alertAdmins(List<String> lines) {
        String subject = "ORPEX : " + lines.size() + " envoi(s) en échec ou à vérifier";
        String body = "Bonjour,\n\nNouveaux envois en échec ou à vérifier :\n\n" + String.join("\n", lines)
                + "\n\nJournal : " + url() + "/journal";
        for (UserAccount admin : accounts.findAllByRoleAndEnabledTrueOrderByEmailAsc(Role.ADMIN)) {
            try {
                gateway.send(new MailMessage(senders.current().from(), admin.getEmail(), subject, body));
                audit.record(AuditEvent.FAILURE_ALERT_SENT, null, "USER", admin.getEmail(), null,
                        lines.size() + " envoi(s) signalé(s) à l'administrateur");
            } catch (MailSendException e) {
                log.warn("Alerte d'échec non envoyée à l'administrateur {} : {}", admin.getEmail(), e.getMessage());
            }
        }
    }

    private String line(Occurrence o, long ownerId) {
        Rule rule = rules.findByIdAndOwnerId(o.getRuleId(), ownerId).orElse(null);
        return "- " + (rule == null ? "?" : rule.getBusinessId()) + " → "
                + (rule == null ? "?" : rule.getRecipientEmail()) + ", prévu le "
                + WHEN.format(o.getScheduledAt().atZone(clock.getZone())) + " : "
                + (o.getState() == OccurrenceState.TO_CHECK ? "à vérifier (arrêt pendant l'envoi)" : "échec");
    }

    private String url() {
        return properties.appUrl().replaceAll("/+$", "");
    }
}
