package com.orpex.mail;

import com.orpex.config.OrpexProperties;
import com.orpex.crypto.SecretCipher;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collection;
import java.util.Properties;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Connexion SMTP à utiliser : celle de l'écran d'administration ({@code smtp_settings}) si elle existe, sinon celle
 * de la configuration ({@code spring.mail.*}, Mailpit en développement). Reconstruite quand l'administrateur la change.
 */
@Component
public class MailSenderProvider {

    /** Connexion, adresse et nom d'expéditeur en vigueur. */
    public record Current(JavaMailSender sender, String from, String fromName) {
    }

    private final SmtpSettingsRepository settings;
    private final JavaMailSender fallback;
    private final OrpexProperties properties;
    private final SecretCipher cipher;
    private final String heloName;

    private Instant builtFor;
    private Current built;

    public MailSenderProvider(SmtpSettingsRepository settings, JavaMailSender fallback, OrpexProperties properties,
            SecretCipher cipher, @Value("${spring.mail.properties.mail.smtp.localhost:orpex.local}") String heloName) {
        this.settings = settings;
        this.fallback = fallback;
        this.properties = properties;
        this.cipher = cipher;
        this.heloName = heloName;
    }

    @Transactional(readOnly = true)
    public synchronized Current current() {
        return settings.findById(SmtpSettings.SINGLE_ROW).map(this::fromDatabase)
                .orElseGet(() -> new Current(fallback, properties.mail().from(), properties.mail().fromName()));
    }

    private Current fromDatabase(SmtpSettings s) {
        if (built == null || !s.getUpdatedAt().equals(builtFor)) {
            built = new Current(build(s), s.getFromAddress(),
                    s.getFromName() == null ? properties.mail().fromName() : s.getFromName());
            builtFor = s.getUpdatedAt();
        }
        return built;
    }

    private JavaMailSender build(SmtpSettings s) {
        JavaMailSenderImpl sender = new JavaMailSenderImpl();
        sender.setHost(s.getHost());
        sender.setPort(s.getPort());
        sender.setDefaultEncoding("UTF-8");
        Properties props = sender.getJavaMailProperties();
        props.put("mail.smtp.localhost", heloName);
        props.put("mail.smtp.connectiontimeout", "10000");
        props.put("mail.smtp.timeout", "30000");
        props.put("mail.smtp.writetimeout", "30000");
        if (s.getUsername() != null) {
            sender.setUsername(s.getUsername());
            sender.setPassword(s.hasPassword() ? cipher.decrypt(s.getPasswordEncrypted()) : null);
            props.put("mail.smtp.auth", "true");
        }
        switch (s.getEncryption()) {
            case STARTTLS -> {
                props.put("mail.smtp.starttls.enable", "true");
                props.put("mail.smtp.starttls.required", "true");
            }
            case TLS -> props.put("mail.smtp.ssl.enable", "true");
            case NONE -> { }
        }
        if (s.getEncryption() != SmtpSettings.Encryption.NONE) {
            props.put("mail.smtp.ssl.checkserveridentity", "true");
            if (s.getCaCertificate() != null) {
                props.put("mail.smtp.ssl.socketFactory", sslContext(s.getCaCertificate()).getSocketFactory());
            }
        }
        return sender;
    }

    /** Certificats PEM lus, pour valider ce que l'administrateur colle. */
    static Collection<? extends Certificate> parseCertificates(String pem) throws CertificateException {
        Collection<? extends Certificate> certificates = CertificateFactory.getInstance("X.509")
                .generateCertificates(new ByteArrayInputStream(pem.getBytes(StandardCharsets.US_ASCII)));
        if (certificates.isEmpty()) {
            throw new CertificateException("aucun certificat");
        }
        return certificates;
    }

    /** Autorités de confiance du JDK, plus l'autorité interne de la banque. */
    private static SSLContext sslContext(String caPem) {
        try {
            KeyStore store = KeyStore.getInstance(KeyStore.getDefaultType());
            store.load(null, null);
            int i = 0;
            for (Certificate certificate : parseCertificates(caPem)) {
                store.setCertificateEntry("orpex-ca-" + i++, certificate);
            }
            X509TrustManager internal = trustManager(store);
            X509TrustManager defaults = trustManager(null);
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, new TrustManager[] {new EitherTrustManager(defaults, internal)}, null);
            return context;
        } catch (GeneralSecurityException | IOException e) {
            throw new IllegalStateException("Certificat d'autorité interne inutilisable : " + e.getMessage(), e);
        }
    }

    private static X509TrustManager trustManager(KeyStore store) throws GeneralSecurityException {
        TrustManagerFactory factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        factory.init(store);
        for (TrustManager manager : factory.getTrustManagers()) {
            if (manager instanceof X509TrustManager x509) {
                return x509;
            }
        }
        throw new GeneralSecurityException("aucun gestionnaire de confiance X.509");
    }

    /** Accepte un serveur reconnu par les autorités du JDK ou par l'autorité interne. */
    private record EitherTrustManager(X509TrustManager first, X509TrustManager second) implements X509TrustManager {

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            first.checkClientTrusted(chain, authType);
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            try {
                first.checkServerTrusted(chain, authType);
            } catch (CertificateException notPublic) {
                second.checkServerTrusted(chain, authType);
            }
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            X509Certificate[] a = first.getAcceptedIssuers();
            X509Certificate[] b = second.getAcceptedIssuers();
            X509Certificate[] all = Arrays.copyOf(a, a.length + b.length);
            System.arraycopy(b, 0, all, a.length, b.length);
            return all;
        }
    }
}
