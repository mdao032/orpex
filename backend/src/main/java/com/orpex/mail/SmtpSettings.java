package com.orpex.mail;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** Configuration du relais SMTP (table {@code smtp_settings}, une seule ligne d'identifiant 1). */
@Entity
@Table(name = "smtp_settings")
public class SmtpSettings {

    static final short SINGLE_ROW = 1;

    public enum Encryption { NONE, STARTTLS, TLS }

    @Id
    private Short id = SINGLE_ROW;

    @Column(nullable = false)
    private String host;

    @Column(nullable = false)
    private int port;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Encryption encryption;

    private String username;

    /** Mot de passe chiffré (AES-GCM) ; la clé n'est jamais en base. */
    @Column(name = "password_encrypted")
    private byte[] passwordEncrypted;

    @Column(name = "from_address", nullable = false, length = 320)
    private String fromAddress;

    @Column(name = "from_name", length = 200)
    private String fromName;

    @Column(name = "ca_certificate", columnDefinition = "text")
    private String caCertificate;

    @Column(name = "health_status", nullable = false, length = 20)
    private String healthStatus = "UNKNOWN";

    @Column(name = "health_checked_at")
    private Instant healthCheckedAt;

    @Column(name = "health_detail", columnDefinition = "text")
    private String healthDetail;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected SmtpSettings() {
        // JPA
    }

    static SmtpSettings create() {
        return new SmtpSettings();
    }

    void update(String host, int port, Encryption encryption, String username, String fromAddress, String fromName,
            String caCertificate, Instant now) {
        this.host = host;
        this.port = port;
        this.encryption = encryption;
        this.username = username;
        this.fromAddress = fromAddress;
        this.fromName = fromName;
        this.caCertificate = caCertificate;
        this.updatedAt = now;
    }

    /** Résultat du dernier contrôle de santé ; ne change pas {@code updatedAt} (la connexion n'est pas reconstruite). */
    void recordHealth(boolean ok, String detail, Instant now) {
        this.healthStatus = ok ? "OK" : "ERROR";
        this.healthDetail = ok ? null : detail;
        this.healthCheckedAt = now;
    }

    void setPasswordEncrypted(byte[] passwordEncrypted) {
        this.passwordEncrypted = passwordEncrypted;
    }

    public String getHost() {
        return host;
    }

    public int getPort() {
        return port;
    }

    public Encryption getEncryption() {
        return encryption;
    }

    public String getUsername() {
        return username;
    }

    byte[] getPasswordEncrypted() {
        return passwordEncrypted;
    }

    public boolean hasPassword() {
        return passwordEncrypted != null;
    }

    public String getFromAddress() {
        return fromAddress;
    }

    public String getFromName() {
        return fromName;
    }

    public String getCaCertificate() {
        return caCertificate;
    }

    public String getHealthStatus() {
        return healthStatus;
    }

    public Instant getHealthCheckedAt() {
        return healthCheckedAt;
    }

    public String getHealthDetail() {
        return healthDetail;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
