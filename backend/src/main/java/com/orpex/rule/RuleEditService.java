package com.orpex.rule;

import com.orpex.core.calendar.ScheduleCalculator;
import com.orpex.core.calendar.SendMode;
import com.orpex.core.importing.Columns;
import com.orpex.core.importing.ImportedRule;
import com.orpex.core.importing.RowValidation;
import com.orpex.core.importing.RowValidator;
import com.orpex.core.importing.RuleStatus;
import com.orpex.core.mail.MessageTemplate;
import com.orpex.holiday.HolidayService;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Modification d'une relance à l'écran (spécification §7 et §9) : mêmes règles qu'à l'import, envois futurs non
 * commencés recalculés, passage de la validation quotidienne de oui à non seulement après confirmation.
 */
@Service
public class RuleEditService {

    private static final Logger log = LoggerFactory.getLogger(RuleEditService.class);
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");

    /** Nouvelle configuration saisie ; l'identifiant, le statut et les colonnes libres ne se modifient pas ici. */
    public record Edit(String email, String firstName, String lastName, String caseReference, String subject,
                       String body, SendMode sendMode, LocalDate sendDate, LocalDate dueDate, Integer daysBefore,
                       LocalDate startDate, LocalDate endDate, LocalTime sendTime, Boolean businessDaysOnly,
                       Boolean dailyValidation) {
    }

    public record EditResult(List<FieldChanges.Change> changes, int sendsAdded, int sendsMoved, int sendsCancelled) {
    }

    private final RuleRepository rules;
    private final RescheduleService rescheduler;
    private final HolidayService holidayService;
    private final Clock clock;

    public RuleEditService(RuleRepository rules, RescheduleService rescheduler, HolidayService holidayService,
            Clock clock) {
        this.rules = rules;
        this.rescheduler = rescheduler;
        this.holidayService = holidayService;
        this.clock = clock;
    }

    /**
     * {@code confirmValidationDrop} : l'utilisateur a confirmé que les envois partiront désormais sans validation
     * (exigé si la validation quotidienne passe de oui à non).
     */
    @Transactional
    public EditResult edit(long ownerId, long ruleId, Edit edit, boolean confirmValidationDrop) {
        Rule rule = rules.findForUpdateByIdAndOwnerId(ruleId, ownerId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Relance introuvable."));
        if (rule.getStatus() == RuleStatus.CLOSED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Le dossier " + rule.getBusinessId() + " est clos : il ne peut plus être modifié.");
        }

        LocalDate today = LocalDate.now(clock);
        Map<String, String> cells = cells(rule, edit);
        RowValidation validation = new RowValidator(holidayService.businessCalendar()).validate(cells, today);
        if (!validation.errors().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Modification refusée : " + String.join(" ; ", validation.errors()) + ".");
        }
        ImportedRule edited = validation.rule();
        ScheduleCalculator calculator = new ScheduleCalculator(holidayService.businessCalendar());
        if (calculator.sendDates(edited.scheduling()).stream().noneMatch(day -> !day.isBefore(today))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Modification refusée : ce calendrier ne prévoit "
                    + "aucun envoi à venir. Pour arrêter les relances, clôturez le dossier.");
        }

        Map<String, String> values = MessageTemplate.valuesUsed(cells, edited.subject(), edited.body());
        List<FieldChanges.Change> changes = FieldChanges.between(rule, edited, FieldChanges.freeVariables(values));
        if (changes.isEmpty()) {
            return new EditResult(List.of(), 0, 0, 0);
        }
        boolean validationDropped = rule.isDailyValidation() && !edited.dailyValidation();
        if (validationDropped && !confirmValidationDrop) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Confirmez le passage de la validation quotidienne "
                    + "de oui à non : les envois partiront ensuite sans validation.");
        }

        Instant now = clock.instant();
        rule.editInApp(edited, values, changes, now);
        int added = 0;
        int moved = 0;
        int cancelled = 0;
        if (rule.isActivated()) {
            SendsDiff diff = rescheduler.diff(rule, calculator, now);
            rescheduler.apply(rule, diff, now);
            added = diff.added();
            moved = diff.moved();
            cancelled = diff.cancelled();
        }
        // Traçabilité (spécification §11) en attendant le journal d'audit (phase 8).
        changes.forEach(change -> log.info("Relance {} (id {}) modifiée à l'écran par l'utilisateur {} : {} « {} » → « {} »",
                rule.getBusinessId(), rule.getId(), ownerId, change.field(), change.before(), change.after()));
        if (validationDropped) {
            log.info("Relance {} (id {}) : passage de la validation quotidienne de oui à non confirmé par l'utilisateur {}",
                    rule.getBusinessId(), rule.getId(), ownerId);
        }
        return new EditResult(changes, added, moved, cancelled);
    }

    /** La saisie mise sous la forme d'une ligne du fichier, pour la valider avec les mêmes règles qu'à l'import. */
    private static Map<String, String> cells(Rule rule, Edit edit) {
        Map<String, String> cells = new HashMap<>(rule.getVariables());
        cells.put(Columns.ID, rule.getBusinessId());
        cells.put(Columns.EMAIL, text(edit.email()));
        cells.put(Columns.FIRST_NAME, text(edit.firstName()));
        cells.put(Columns.LAST_NAME, text(edit.lastName()));
        cells.put(Columns.CASE_REFERENCE, text(edit.caseReference()));
        cells.put(Columns.SUBJECT, text(edit.subject()));
        cells.put(Columns.BODY, text(edit.body()));
        cells.put(Columns.SEND_MODE, edit.sendMode() == null ? "" : edit.sendMode().name().toLowerCase(Locale.ROOT));
        cells.put(Columns.SEND_DATE, date(edit.sendDate()));
        cells.put(Columns.DUE_DATE, date(edit.dueDate()));
        cells.put(Columns.DAYS_BEFORE, edit.daysBefore() == null ? "" : edit.daysBefore().toString());
        cells.put(Columns.START_DATE, date(edit.startDate()));
        cells.put(Columns.END_DATE, date(edit.endDate()));
        cells.put(Columns.SEND_TIME, edit.sendTime() == null ? "" : TIME.format(edit.sendTime()));
        cells.put(Columns.BUSINESS_DAYS_ONLY, yesNo(edit.businessDaysOnly()));
        cells.put(Columns.DAILY_VALIDATION, yesNo(edit.dailyValidation()));
        cells.put(Columns.STATUS, "actif");
        return cells;
    }

    private static String text(String value) {
        return value == null ? "" : value;
    }

    private static String date(LocalDate value) {
        return value == null ? "" : value.toString();
    }

    private static String yesNo(Boolean value) {
        return value == null ? "" : value ? "oui" : "non";
    }
}
