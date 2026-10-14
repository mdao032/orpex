package com.orpex.rule;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;

/**
 * Un envoi planifié : une ligne, un jour (table {@code occurrences}, unique par ligne et par jour). Jamais supprimé :
 * un envoi annulé change d'état (trigger en base).
 */
@Entity
@Table(name = "occurrences")
public class Occurrence {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "rule_id", nullable = false, updatable = false)
    private Long ruleId;

    @Column(name = "scheduled_date", nullable = false, updatable = false)
    private LocalDate scheduledDate;

    /** Date et heure d'envoi dans le fuseau de l'installation, en instant absolu. */
    @Column(name = "scheduled_at", nullable = false)
    private Instant scheduledAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private OccurrenceState state;

    @Column(name = "created_by", nullable = false, updatable = false)
    private Long createdBy;

    @Column(name = "validated_by")
    private Long validatedBy;

    @Column(name = "validated_at")
    private Instant validatedAt;

    @Column(name = "sent_at")
    private Instant sentAt;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "last_error", columnDefinition = "text")
    private String lastError;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Occurrence() {
        // JPA
    }

    public Occurrence(long ruleId, LocalDate scheduledDate, Instant scheduledAt, OccurrenceState state,
            long createdBy, Instant now) {
        this.ruleId = ruleId;
        this.scheduledDate = scheduledDate;
        this.scheduledAt = scheduledAt;
        this.state = state;
        this.createdBy = createdBy;
        this.createdAt = now;
        this.updatedAt = now;
    }

    /** Envoi futur abandonné (dossier clos…) : il change d'état, il n'est jamais supprimé. */
    public void cancel(Instant now) {
        this.state = OccurrenceState.CANCELLED;
        this.updatedAt = now;
    }

    /**
     * Le jour redevient un jour d'envoi (jour férié retiré) : un envoi annulé reprend, puisqu'un envoi ne peut exister
     * qu'une fois par ligne et par jour.
     */
    public void revive(OccurrenceState upcoming, Instant now) {
        if (state != OccurrenceState.CANCELLED) {
            throw new IllegalStateException("Seul un envoi annulé peut reprendre (état : " + state + ")");
        }
        this.state = upcoming;
        this.updatedAt = now;
    }

    /** Réservé par le worker : il part maintenant (jamais deux fois, grâce au verrou et à cet état). */
    public void markSending(Instant now) {
        this.state = OccurrenceState.SENDING;
        this.updatedAt = now;
    }

    /** Accepté par le serveur de messagerie. */
    public void markSent(Instant now) {
        this.state = OccurrenceState.SENT;
        this.attempts++;
        this.sentAt = now;
        this.lastError = null;
        this.updatedAt = now;
    }

    public void markFailed(String error, Instant now) {
        this.state = OccurrenceState.FAILED;
        this.attempts++;
        this.lastError = error;
        this.updatedAt = now;
    }

    public Long getId() {
        return id;
    }

    public Long getRuleId() {
        return ruleId;
    }

    public LocalDate getScheduledDate() {
        return scheduledDate;
    }

    public Instant getScheduledAt() {
        return scheduledAt;
    }

    public OccurrenceState getState() {
        return state;
    }

    public Long getCreatedBy() {
        return createdBy;
    }

    public Long getValidatedBy() {
        return validatedBy;
    }

    public Instant getValidatedAt() {
        return validatedAt;
    }

    public Instant getSentAt() {
        return sentAt;
    }

    public int getAttempts() {
        return attempts;
    }

    public String getLastError() {
        return lastError;
    }
}
