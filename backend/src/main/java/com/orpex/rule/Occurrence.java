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

    /** Nature du dernier problème (ou de la décision « à vérifier »), pour un message clair ; null s'il n'y en a pas. */
    @Enumerated(EnumType.STRING)
    @Column(name = "error_kind", length = 30)
    private SendIssue issue;

    /** Prochaine tentative après un échec temporaire ; null sinon. */
    @Column(name = "next_attempt_at")
    private Instant nextAttemptAt;

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

    /** Validation du jour par le propriétaire (spécification §7) : l'envoi partira à son heure. */
    public void validate(long actorId, Instant now) {
        requireAwaitingValidation();
        this.state = OccurrenceState.VALIDATED;
        this.validatedBy = actorId;
        this.validatedAt = now;
        this.updatedAt = now;
    }

    /** Décoché par le propriétaire lors de la validation du jour : il ne partira pas. */
    public void reject(long actorId, Instant now) {
        requireAwaitingValidation();
        this.state = OccurrenceState.NOT_VALIDATED;
        this.validatedBy = actorId;
        this.validatedAt = now;
        this.updatedAt = now;
    }

    private void requireAwaitingValidation() {
        if (state != OccurrenceState.AWAITING_VALIDATION) {
            throw new IllegalStateException("Envoi " + id + " : pas en attente de validation (état " + state + ")");
        }
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
        this.issue = null;
        this.nextAttemptAt = null;
        this.updatedAt = now;
    }

    /** Échec définitif : plus aucune tentative. */
    public void markFailed(SendIssue issue, String error, Instant now) {
        this.state = OccurrenceState.FAILED;
        this.attempts++;
        this.issue = issue;
        this.lastError = error;
        this.nextAttemptAt = null;
        this.updatedAt = now;
    }

    /**
     * Décision de l'utilisateur sur un envoi « à vérifier » (arrêt pendant l'envoi) : parti ou non. Jamais de renvoi :
     * un envoi non parti passe en échec.
     */
    public void resolveToCheck(boolean sent, Instant now) {
        if (state != OccurrenceState.TO_CHECK) {
            throw new IllegalStateException("Envoi " + id + " : pas à vérifier (état " + state + ")");
        }
        this.state = sent ? OccurrenceState.SENT : OccurrenceState.FAILED;
        this.issue = sent ? SendIssue.CONFIRMED_SENT : SendIssue.CONFIRMED_NOT_SENT;
        this.lastError = sent ? "parti (confirmé par l'utilisateur après vérification)"
                : "non parti (confirmé par l'utilisateur après vérification)";
        this.updatedAt = now;
    }

    /**
     * Échec temporaire : l'envoi reprend son état d'attente ({@code PLANNED}, ou {@code VALIDATED} s'il avait été
     * validé) et repartira à {@code nextAttempt}.
     */
    public void scheduleRetry(SendIssue issue, String error, Instant nextAttempt, OccurrenceState waitingState,
            Instant now) {
        this.state = waitingState;
        this.attempts++;
        this.issue = issue;
        this.lastError = error;
        this.nextAttemptAt = nextAttempt;
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

    public SendIssue getIssue() {
        return issue;
    }

    public Instant getNextAttemptAt() {
        return nextAttemptAt;
    }
}
