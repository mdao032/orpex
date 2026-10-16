package com.orpex.account;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;

/** Compte local (table {@code users}). L'email est stocké normalisé (sans espaces, en minuscules). */
@Entity
@Table(name = "users")
public class UserAccount {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 320)
    private String email;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Role role;

    @Column(nullable = false)
    private boolean enabled = true;

    @Column(name = "failed_attempts", nullable = false)
    private int failedAttempts;

    @Column(name = "locked_until")
    private Instant lockedUntil;

    @Column(name = "last_login_at")
    private Instant lastLoginAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "must_change_password", nullable = false)
    private boolean mustChangePassword;

    @Enumerated(EnumType.STRING)
    @Column(name = "summary_frequency", nullable = false, length = 20)
    private SummaryFrequency summaryFrequency = SummaryFrequency.DAILY;

    protected UserAccount() {
        // JPA
    }

    public UserAccount(String email, String passwordHash, Role role) {
        this.email = normalizeEmail(email);
        this.passwordHash = passwordHash;
        this.role = role;
        this.createdAt = Instant.now();
    }

    /** Compte dont le mot de passe a été choisi par quelqu'un d'autre : il devra le changer à sa première connexion. */
    public static UserAccount withTemporaryPassword(String email, String passwordHash, Role role) {
        UserAccount account = new UserAccount(email, passwordHash, role);
        account.mustChangePassword = true;
        return account;
    }

    /** Forme sous laquelle un email est stocké et recherché : deux saisies de la même adresse désignent un seul compte. */
    public static String normalizeEmail(String email) {
        return email == null ? null : email.strip().toLowerCase(Locale.ROOT);
    }

    /**
     * Nouveau hachage du même mot de passe (conversion bcrypt → Argon2id) : le caractère provisoire est conservé.
     * {@code passwordHash} est déjà haché, jamais un mot de passe en clair.
     */
    public void changePasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
    }

    /** L'utilisateur a choisi lui-même son nouveau mot de passe. */
    public void changePassword(String passwordHash) {
        this.passwordHash = passwordHash;
        this.mustChangePassword = false;
    }

    /** Mot de passe défini par un administrateur : provisoire, à changer à la connexion suivante. */
    public void resetPassword(String passwordHash) {
        this.passwordHash = passwordHash;
        this.mustChangePassword = true;
    }

    public void enable() {
        this.enabled = true;
    }

    public void changeRole(Role role) {
        this.role = role;
    }

    /** Verrouillage temporaire en cours : la connexion est refusée sans même vérifier le mot de passe. */
    public boolean isLockedAt(Instant now) {
        return lockedUntil != null && lockedUntil.isAfter(now);
    }

    /** Échec de connexion : au {@code maxFailedAttempts}-ième échec consécutif, le compte est verrouillé. */
    public void recordFailedLogin(int maxFailedAttempts, Duration lockDuration, Instant now) {
        failedAttempts++;
        if (failedAttempts >= maxFailedAttempts) {
            lockedUntil = now.plus(lockDuration);
            failedAttempts = 0;
        }
    }

    public void recordSuccessfulLogin(Instant now) {
        failedAttempts = 0;
        lockedUntil = null;
        lastLoginAt = now;
    }

    /** Un compte désactivé ne peut plus se connecter ; il n'est jamais supprimé (il reste cité par l'historique). */
    public void disable() {
        this.enabled = false;
    }

    public void changeSummaryFrequency(SummaryFrequency frequency) {
        this.summaryFrequency = frequency;
    }

    public SummaryFrequency getSummaryFrequency() {
        return summaryFrequency;
    }

    public Long getId() {
        return id;
    }

    public String getEmail() {
        return email;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public Role getRole() {
        return role;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public int getFailedAttempts() {
        return failedAttempts;
    }

    public Instant getLockedUntil() {
        return lockedUntil;
    }

    public Instant getLastLoginAt() {
        return lastLoginAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public boolean isMustChangePassword() {
        return mustChangePassword;
    }
}
