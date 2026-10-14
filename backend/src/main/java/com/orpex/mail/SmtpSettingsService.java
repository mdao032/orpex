package com.orpex.mail;

import com.orpex.config.OrpexProperties;
import com.orpex.crypto.SecretCipher;
import java.security.cert.CertificateException;
import java.time.Clock;
import java.time.Instant;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Configuration du relais SMTP par l'administrateur (spécification §10). Le mot de passe est chiffré avant d'être
 * stocké, n'est jamais renvoyé et n'apparaît dans aucun journal.
 */
@Service
public class SmtpSettingsService {

    private static final Logger log = LoggerFactory.getLogger(SmtpSettingsService.class);
    private static final Pattern EMAIL = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");

    /** Ce que l'écran affiche ; {@code configured} faux : la configuration du fichier est en vigueur. */
    public record View(boolean configured, String host, int port, SmtpSettings.Encryption encryption, String username,
                       boolean passwordSet, String fromAddress, String fromName, String caCertificate,
                       boolean encryptionKeyAvailable, Health health) {
    }

    /** Dernier contrôle de santé du relais : {@code status} UNKNOWN (pas encore contrôlé), OK ou ERROR. */
    public record Health(String status, Instant checkedAt, String detail) {
    }

    /**
     * {@code password} : nouveau mot de passe, ou null/vide pour garder l'actuel ; {@code removePassword} pour le
     * supprimer (relais qui fait confiance à l'adresse IP du serveur).
     */
    public record Change(String host, Integer port, SmtpSettings.Encryption encryption, String username,
                         String password, Boolean removePassword, String fromAddress, String fromName,
                         String caCertificate) {
    }

    private final SmtpSettingsRepository repository;
    private final SecretCipher cipher;
    private final OrpexProperties properties;
    private final Clock clock;
    private final String fileHost;
    private final int filePort;

    public SmtpSettingsService(SmtpSettingsRepository repository, SecretCipher cipher, OrpexProperties properties,
            Clock clock, @Value("${spring.mail.host:localhost}") String fileHost,
            @Value("${spring.mail.port:25}") int filePort) {
        this.repository = repository;
        this.cipher = cipher;
        this.properties = properties;
        this.clock = clock;
        this.fileHost = fileHost;
        this.filePort = filePort;
    }

    @Transactional(readOnly = true)
    public View view() {
        return repository.findById(SmtpSettings.SINGLE_ROW)
                .map(s -> new View(true, s.getHost(), s.getPort(), s.getEncryption(), s.getUsername(), s.hasPassword(),
                        s.getFromAddress(), s.getFromName(), s.getCaCertificate(), cipher.isAvailable(),
                        new Health(s.getHealthStatus(), s.getHealthCheckedAt(), s.getHealthDetail())))
                .orElseGet(() -> new View(false, fileHost, filePort, SmtpSettings.Encryption.NONE, null, false,
                        properties.mail().from(), properties.mail().fromName(), null, cipher.isAvailable(),
                        new Health("UNKNOWN", null, null)));
    }

    @Transactional
    public View save(Change change, long adminId) {
        String host = trimmed(change.host());
        if (host == null) {
            throw badRequest("Saisissez l'adresse du serveur SMTP.");
        }
        if (change.port() == null || change.port() < 1 || change.port() > 65535) {
            throw badRequest("Le port doit être compris entre 1 et 65535.");
        }
        if (change.encryption() == null) {
            throw badRequest("Choisissez le mode de chiffrement.");
        }
        String from = trimmed(change.fromAddress());
        if (from == null || !EMAIL.matcher(from).matches()) {
            throw badRequest("Saisissez une adresse expéditrice valide.");
        }
        String ca = trimmed(change.caCertificate());
        if (ca != null) {
            try {
                MailSenderProvider.parseCertificates(ca);
            } catch (CertificateException | RuntimeException e) {
                throw badRequest("Certificat d'autorité illisible : collez-le au format PEM "
                        + "(-----BEGIN CERTIFICATE----- …).");
            }
        }
        String username = trimmed(change.username());
        String password = change.password() == null || change.password().isEmpty() ? null : change.password();
        if (password != null && !cipher.isAvailable()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Impossible d'enregistrer un mot de passe : aucune "
                    + "clé de chiffrement n'est configurée sur le serveur (ORPEX_SECRET_KEY).");
        }

        Instant now = clock.instant();
        SmtpSettings settings = repository.findById(SmtpSettings.SINGLE_ROW).orElseGet(SmtpSettings::create);
        settings.update(host, change.port(), change.encryption(), username, from, trimmed(change.fromName()), ca, now);
        if (username == null || Boolean.TRUE.equals(change.removePassword())) {
            settings.setPasswordEncrypted(null);
        } else if (password != null) {
            settings.setPasswordEncrypted(cipher.encrypt(password));
        }
        repository.save(settings);
        // Jamais le mot de passe dans les journaux (traçabilité complète : journal d'audit, phase 8).
        log.info("Configuration SMTP modifiée par l'administrateur {} : {}:{} ({}), utilisateur {}, mot de passe {}",
                adminId, host, change.port(), change.encryption(), username == null ? "aucun" : username,
                settings.hasPassword() ? "enregistré" : "aucun");
        return view();
    }

    private static String trimmed(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private static ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
