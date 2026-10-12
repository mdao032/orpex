package com.orpex.holiday;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;

/** Jour férié configuré par l'administrateur (table {@code calendar_days}). */
@Entity
@Table(name = "calendar_days")
public class Holiday {

    @Id
    private LocalDate day;

    @Column(nullable = false, length = 200)
    private String label;

    protected Holiday() {
        // JPA
    }

    public Holiday(LocalDate day, String label) {
        this.day = day;
        this.label = label.strip();
    }

    public LocalDate getDay() {
        return day;
    }

    public String getLabel() {
        return label;
    }
}
