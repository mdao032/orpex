package com.orpex.rule;

import com.orpex.core.calendar.SchedulingRule;
import com.orpex.core.calendar.SendMode;
import com.orpex.core.importing.ImportedRule;
import com.orpex.core.importing.RuleStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.LinkedHashMap;
import java.util.Map;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Une ligne du fichier Excel : la configuration d'une relance (table {@code rules}). L'état des envois est stocké à
 * part ({@code occurrences}) : un import ne peut jamais l'écraser (spécification §12).
 */
@Entity
@Table(name = "rules")
public class Rule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "owner_id", nullable = false, updatable = false)
    private Long ownerId;

    /** Colonne {@code id} du fichier : clé du ré-import, unique par propriétaire. */
    @Column(name = "business_id", nullable = false, length = 100, updatable = false)
    private String businessId;

    @Column(name = "recipient_email", nullable = false, length = 320)
    private String recipientEmail;

    @Column(name = "first_name", length = 200)
    private String firstName;

    @Column(name = "last_name", length = 200)
    private String lastName;

    @Column(name = "case_reference", length = 200)
    private String caseReference;

    @Column(nullable = false, length = 500)
    private String subject;

    @Column(nullable = false, columnDefinition = "text")
    private String body;

    @Enumerated(EnumType.STRING)
    @Column(name = "send_mode", nullable = false, length = 20)
    private SendMode sendMode;

    @Column(name = "send_date")
    private LocalDate sendDate;

    @Column(name = "due_date")
    private LocalDate dueDate;

    @Column(name = "days_before")
    private Integer daysBefore;

    @Column(name = "start_date")
    private LocalDate startDate;

    @Column(name = "end_date")
    private LocalDate endDate;

    @Column(name = "send_time", nullable = false)
    private LocalTime sendTime;

    @Column(name = "business_days_only", nullable = false)
    private boolean businessDaysOnly;

    @Column(name = "daily_validation", nullable = false)
    private boolean dailyValidation;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private RuleStatus status;

    @Column(nullable = false)
    private boolean paused;

    /** Empreinte des colonnes de configuration du fichier (détection « modifiée / inchangée » au ré-import). */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "config_hash", nullable = false, length = 64)
    private String configHash;

    @Column(name = "created_by", nullable = false, updatable = false)
    private Long createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** Valeurs des variables du message relevées à l'import, y compris des colonnes libres (ex. {{montant}}). */
    @Convert(converter = StringMapJsonConverter.class)
    @Column(nullable = false, columnDefinition = "text")
    private Map<String, String> variables = new LinkedHashMap<>();

    /** Activation par l'utilisateur (bouton) : avant, aucun envoi n'existe pour cette ligne. */
    @Column(name = "activated_at")
    private Instant activatedAt;

    protected Rule() {
        // JPA
    }

    /** Nouvelle ligne issue d'un import : en V1, le propriétaire est celui qui importe. */
    public static Rule fromImport(ImportedRule imported, String configHash, long ownerId, Instant now) {
        return fromImport(imported, configHash, Map.of(), ownerId, now);
    }

    /** {@code variables} : valeurs des variables utilisées dans l'objet et le contenu, relevées dans la ligne. */
    public static Rule fromImport(ImportedRule imported, String configHash, Map<String, String> variables,
            long ownerId, Instant now) {
        Rule rule = new Rule();
        rule.ownerId = ownerId;
        rule.businessId = imported.businessId();
        rule.createdBy = ownerId;
        rule.createdAt = now;
        rule.apply(imported, configHash, now);
        rule.variables = new LinkedHashMap<>(variables);
        return rule;
    }

    /**
     * Valeurs pour remplacer les variables du message : celles relevées à l'import, complétées par les champs de la
     * ligne (colonnes standard), utiles pour une ligne importée avant l'enregistrement des valeurs.
     */
    public Map<String, String> variableValues() {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("id", businessId);
        values.put("email", recipientEmail);
        putIfPresent(values, "prenom", firstName);
        putIfPresent(values, "nom", lastName);
        putIfPresent(values, "reference_dossier", caseReference);
        values.putAll(variables);
        return values;
    }

    private static void putIfPresent(Map<String, String> values, String name, String value) {
        if (value != null && !value.isBlank()) {
            values.put(name, value);
        }
    }

    /** Remplace toute la configuration par celle du fichier ; l'état des envois n'est pas touché. */
    public void apply(ImportedRule imported, String configHash, Instant now) {
        SchedulingRule scheduling = imported.scheduling();
        this.recipientEmail = imported.recipientEmail();
        this.firstName = blankToNull(imported.firstName());
        this.lastName = blankToNull(imported.lastName());
        this.caseReference = blankToNull(imported.caseReference());
        this.subject = imported.subject();
        this.body = imported.body();
        this.sendMode = scheduling.mode();
        this.sendDate = scheduling.sendDate();
        this.dueDate = scheduling.dueDate();
        this.daysBefore = scheduling.daysBefore();
        this.startDate = scheduling.startDate();
        this.endDate = scheduling.endDate();
        this.businessDaysOnly = scheduling.businessDaysOnly();
        this.sendTime = imported.sendTime();
        this.dailyValidation = imported.dailyValidation();
        this.status = imported.status();
        this.configHash = configHash;
        this.updatedAt = now;
    }

    /** Pause de la ligne : ses envois ne partent pas tant qu'elle n'est pas reprise. */
    public void pause(boolean paused, Instant now) {
        this.paused = paused;
        this.updatedAt = now;
    }

    /** Dossier réglé : plus aucun envoi futur (spécification §6, « clore ce dossier »). */
    public void close(Instant now) {
        this.status = RuleStatus.CLOSED;
        this.updatedAt = now;
    }

    public void markActivated(Instant now) {
        this.activatedAt = now;
        this.updatedAt = now;
    }

    /** Calendrier de la ligne, tel que le cœur métier le calcule. */
    public SchedulingRule scheduling() {
        return new SchedulingRule(sendMode, sendDate, dueDate, daysBefore, startDate, endDate, businessDaysOnly);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    public Long getId() {
        return id;
    }

    public Long getOwnerId() {
        return ownerId;
    }

    public String getBusinessId() {
        return businessId;
    }

    public String getRecipientEmail() {
        return recipientEmail;
    }

    public String getFirstName() {
        return firstName;
    }

    public String getLastName() {
        return lastName;
    }

    public String getCaseReference() {
        return caseReference;
    }

    public String getSubject() {
        return subject;
    }

    public String getBody() {
        return body;
    }

    public LocalTime getSendTime() {
        return sendTime;
    }

    public boolean isDailyValidation() {
        return dailyValidation;
    }

    public RuleStatus getStatus() {
        return status;
    }

    public boolean isPaused() {
        return paused;
    }

    public String getConfigHash() {
        return configHash;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Instant getActivatedAt() {
        return activatedAt;
    }

    public boolean isActivated() {
        return activatedAt != null;
    }
}
