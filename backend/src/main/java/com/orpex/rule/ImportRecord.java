package com.orpex.rule;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** Un import appliqué : fichier, auteur, date et compteurs (table {@code imports}, spécification §9). */
@Entity
@Table(name = "imports")
public class ImportRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    @Column(name = "file_name", nullable = false, length = 500, updatable = false)
    private String fileName;

    @Column(name = "created_count", nullable = false)
    private int createdCount;

    @Column(name = "updated_count", nullable = false)
    private int updatedCount;

    @Column(name = "unchanged_count", nullable = false)
    private int unchangedCount;

    @Column(name = "rejected_count", nullable = false)
    private int rejectedCount;

    @Column(columnDefinition = "text")
    private String summary;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected ImportRecord() {
        // JPA
    }

    public ImportRecord(long userId, String fileName, int createdCount, int updatedCount, int unchangedCount,
            int rejectedCount, String summary, Instant createdAt) {
        this.userId = userId;
        this.fileName = fileName;
        this.createdCount = createdCount;
        this.updatedCount = updatedCount;
        this.unchangedCount = unchangedCount;
        this.rejectedCount = rejectedCount;
        this.summary = summary;
        this.createdAt = createdAt;
    }

    public Long getId() {
        return id;
    }

    public Long getUserId() {
        return userId;
    }

    public String getFileName() {
        return fileName;
    }

    public int getCreatedCount() {
        return createdCount;
    }

    public int getUpdatedCount() {
        return updatedCount;
    }

    public int getUnchangedCount() {
        return unchangedCount;
    }

    public int getRejectedCount() {
        return rejectedCount;
    }

    public String getSummary() {
        return summary;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
