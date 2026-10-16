package com.orpex.notification;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalTime;

/** Journée de travail de l'installation (une seule ligne) : le résumé quotidien part à l'heure de fin. */
@Entity
@Table(name = "notification_settings")
public class NotificationSettings {

    static final short SINGLE_ROW = 1;

    @Id
    private Short id;

    @Column(name = "workday_start", nullable = false)
    private LocalTime workdayStart;

    @Column(name = "workday_end", nullable = false)
    private LocalTime workdayEnd;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "updated_by")
    private Long updatedBy;

    protected NotificationSettings() {
        // JPA
    }

    void change(LocalTime start, LocalTime end, long adminId, Instant now) {
        this.workdayStart = start;
        this.workdayEnd = end;
        this.updatedBy = adminId;
        this.updatedAt = now;
    }

    public LocalTime getWorkdayStart() {
        return workdayStart;
    }

    public LocalTime getWorkdayEnd() {
        return workdayEnd;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
