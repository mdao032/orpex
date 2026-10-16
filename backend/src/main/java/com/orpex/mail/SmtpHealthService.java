package com.orpex.mail;

import com.orpex.audit.AuditEvent;
import com.orpex.account.Role;
import com.orpex.account.UserAccount;
import com.orpex.account.UserAccountRepository;
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
    private final UserAccountRepository accounts;

    public SmtpHealthService(MailSenderProvider senders, MailGateway gateway, SmtpSettingsRepository settings,
            TransactionTemplate transaction, Clock clock, AuditLog audit, UserAccountRepository accounts) {
        this.accounts = accounts;
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
        String previous = transaction.execute(status -> settings.findById(SmtpSettings.SINGLE_ROW).map(s -> {
            String before = s.getHealthStatus();
            s.recordHealth(recorded.success(), recorded.detail(), now);
            return before == null ? "" : before;
        }).orElse(null));
        if (previous == null) {
            return result; // relais du fichier (développement) : pas suivi
        }
        // Alerte au changement d'état seulement (spécification §11) : panne, puis rétablissement.
        if (!result.success() && !"ERROR".equals(previous)) {
            alertAdmins("ORPEX : le relais de messagerie ne répond plus", "Bonjour,\n\nLe contrôle du relais SMTP a "
                    + "échoué : " + result.detail() + "\n\nLes envois échoueront tant que le relais ne répond pas. "
                    + "Vérifiez la configuration (écran « Messagerie ») ou suspendez les envois (écran « Envois »).");
        } else if (result.success() && "ERROR".equals(previous)) {
            alertAdmins("ORPEX : le relais de messagerie répond de nouveau", "Bonjour,\n\nLe relais SMTP répond "
                    + "de nouveau. Consultez les envois en échec ou à vérifier pendant la panne.");
        }
        return result;
    }

    /** Le relais peut être celui qui est en panne : l'échec de l'alerte est seulement journalisé. */
    private void alertAdmins(String subject, String body) {
        for (UserAccount admin : accounts.findAllByRoleAndEnabledTrueOrderByEmailAsc(Role.ADMIN)) {
            try {
                gateway.send(new MailMessage(senders.current().from(), admin.getEmail(), subject, body));
                audit.record(AuditEvent.SMTP_ALERT_SENT, null, "USER", admin.getEmail(), null, subject);
            } catch (MailSendException | RuntimeException e) {
                log.warn("Alerte messagerie non envoyée à {} : {}", admin.getEmail(), e.getMessage());
            }
        }
    }
}
