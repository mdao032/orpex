package com.orpex.rule;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** Pause générale des envois (table {@code sending_control}, une seule ligne d'identifiant 1). */
@Entity
@Table(name = "sending_control")
public class SendingControl {

    static final short SINGLE_ROW = 1;

    @Id
    private Short id;

    @Column(nullable = false)
    private boolean paused;

    @Column(name = "changed_at")
    private Instant changedAt;

    @Column(name = "changed_by")
    private Long changedBy;

    protected SendingControl() {
        // JPA
    }

    void change(boolean paused, long actorId, Instant now) {
        this.paused = paused;
        this.changedBy = actorId;
        this.changedAt = now;
    }

    public boolean isPaused() {
        return paused;
    }

    public Instant getChangedAt() {
        return changedAt;
    }

    public Long getChangedBy() {
        return changedBy;
    }
}
