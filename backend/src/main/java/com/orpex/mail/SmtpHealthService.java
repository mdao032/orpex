package com.orpex.mail;

import com.orpex.audit.AuditEvent;
import com.orpex.audit.AuditLog;
import com.orpex.core.mail.MailGateway;
import com.orpex.core.mail.MailMessage;
import com.orpex.core.mail.MailSendException;
import jakarta.mail.MessagingException;
import java.time.Clock;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Vérifications du relais SMTP (spécification §10) : mail de test vers l'administrateur, et contrôle de santé régulier
 * (connexion sans envoi), dont le résultat est enregistré pour l'écran « Messagerie ».
 */
@Service
public class SmtpHealthService {

    private static final Logger log = LoggerFactory.getLogger(SmtpHealthService.class);

    /** Résultat d'une vérification : {@code detail} contient l'erreur du relais en cas d'échec. */
    public record Result(boolean success, String detail) {
    }

    private final MailSenderProvider senders;
    private final MailGateway gateway;
    private final SmtpSettingsRepository settings;
    private final TransactionTemplate transaction;
    private final Clock clock;
    private final AuditLog audit;

    public SmtpHealthService(MailSenderProvider senders, MailGateway gateway, SmtpSettingsRepository settings,
            TransactionTemplate transaction, Clock clock, AuditLog audit) {
        this.senders = senders;
        this.gateway = gateway;
        this.settings = settings;
        this.transaction = transaction;
        this.clock = clock;
        this.audit = audit;
    }

    /** Envoie un mail de test à l'administrateur, avec la configuration en vigueur. */
    public Result sendTestMail(String adminEmail) {
        try {
            gateway.send(new MailMessage(senders.current().from(), adminEmail, "ORPEX : message de test",
                    "Bonjour,\n\nCe message confirme que l'application ORPEX peut envoyer des mails par le relais "
                            + "configuré.\n\nAucune action n'est nécessaire."));
            audit.record(AuditEvent.SMTP_TEST_SENT, "envoyé à " + adminEmail);
            return new Result(true, null);
        } catch (MailSendException e) {
            return new Result(false, e.getMessage());
        }
    }

    /**
     * Contrôle de santé : connexion au relais (et authentification), sans envoyer de mail. Enregistré seulement pour un
     * relais configuré à l'écran ; la configuration du fichier (développement) n'est pas suivie.
     */
    public Result checkHealth() {
        Result result;
        try {
            if (senders.current().sender() instanceof JavaMailSenderImpl impl) {
                impl.testConnection();
            }
            result = new Result(true, null);
        } catch (MessagingException | RuntimeException e) {
            result = new Result(false, e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage().strip());
            log.warn("Contrôle du relais SMTP en échec : {}", result.detail());
        }
        Instant now = clock.instant();
        Result recorded = result;
        transaction.executeWithoutResult(status -> settings.findById(SmtpSettings.SINGLE_ROW)
                .ifPresent(s -> s.recordHealth(recorded.success(), recorded.detail(), now)));
        return result;
    }
}
