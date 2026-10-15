package com.orpex.audit;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.Immutable;

/**
 * Une ligne du journal d'audit (table {@code audit_log}) : jamais modifiée ni supprimée (trigger en base).
 * {@code actorId} nul : action du système (worker) ; {@code ownerId} : propriétaire des relances concernées.
 */
@Entity
@Immutable
@Table(name = "audit_log")
public class AuditEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;

    @Column(name = "actor_id", updatable = false)
    private Long actorId;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, length = 60, updatable = false)
    private AuditEvent eventType;

    @Column(name = "object_type", length = 40, updatable = false)
    private String objectType;

    @Column(name = "object_id", length = 100, updatable = false)
    private String objectId;

    @Column(name = "old_value", columnDefinition = "text", updatable = false)
    private String oldValue;

    @Column(name = "new_value", columnDefinition = "text", updatable = false)
    private String newValue;

    @Column(name = "owner_id", updatable = false)
    private Long ownerId;

    protected AuditEntry() {
        // JPA
    }

    AuditEntry(Instant occurredAt, Long actorId, AuditEvent eventType, String objectType, String objectId,
            String oldValue, String newValue, Long ownerId) {
        this.occurredAt = occurredAt;
        this.actorId = actorId;
        this.eventType = eventType;
        this.objectType = objectType;
        this.objectId = objectId == null || objectId.length() <= 100 ? objectId : objectId.substring(0, 100);
        this.oldValue = oldValue;
        this.newValue = newValue;
        this.ownerId = ownerId;
    }

    public Long getId() {
        return id;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public Long getActorId() {
        return actorId;
    }

    public AuditEvent getEventType() {
        return eventType;
    }

    public String getObjectType() {
        return objectType;
    }

    public String getObjectId() {
        return objectId;
    }

    public String getOldValue() {
        return oldValue;
    }

    public String getNewValue() {
        return newValue;
    }

    public Long getOwnerId() {
        return ownerId;
    }
}
