package com.orpex.importing;

import com.orpex.core.calendar.ActivationPlanner;
import com.orpex.core.calendar.BusinessCalendar;
import com.orpex.core.calendar.ScheduleCalculator;
import com.orpex.core.calendar.SchedulingRule;
import com.orpex.core.calendar.SendMode;
import com.orpex.core.importing.ConfigHash;
import com.orpex.core.importing.FileValidator;
import com.orpex.core.importing.ImportedRule;
import com.orpex.core.importing.RowValidation;
import com.orpex.core.importing.RowValidator;
import com.orpex.core.importing.RuleStatus;
import com.orpex.core.importing.XlsxReader;
import com.orpex.core.mail.MessageTemplate;
import com.orpex.holiday.HolidayService;
import com.orpex.rule.FieldChanges;
import com.orpex.rule.OccurrenceRepository;
import com.orpex.rule.OccurrenceState;
import com.orpex.rule.Rule;
import com.orpex.rule.RuleCells;
import com.orpex.rule.RuleRepository;
import com.orpex.rule.SendsDiff;
import java.io.InputStream;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Aperçu d'un import (spécification §5 et §9) : lit et valide le fichier, compare chaque ligne à la base (créer,
 * modifier, inchangée), détaille les champs modifiés et l'effet sur les envois, liste les lignes absentes du fichier.
 * N'écrit rien : l'utilisateur confirme ensuite en renvoyant le même fichier.
 */
@Service
public class ImportPreviewService {

    public enum Action { CREATE, UPDATE, UNCHANGED, REJECTED }

    /** Valeurs de la ligne telles que l'application les a comprises (dates au format ISO). */
    public record Interpreted(String email, String firstName, String lastName, String caseReference, String subject,
                              SendMode sendMode, LocalDate sendDate, LocalDate dueDate, Integer daysBefore,
                              LocalDate startDate, LocalDate endDate, LocalTime sendTime, boolean businessDaysOnly,
                              boolean dailyValidation, RuleStatus status) {

        static Interpreted of(ImportedRule rule) {
            SchedulingRule s = rule.scheduling();
            return new Interpreted(rule.recipientEmail(), rule.firstName(), rule.lastName(), rule.caseReference(),
                    rule.subject(), s.mode(), s.sendDate(), s.dueDate(), s.daysBefore(), s.startDate(), s.endDate(),
                    rule.sendTime(), s.businessDaysOnly(), rule.dailyValidation(), rule.status());
        }
    }

    /**
     * Envois que l'activation créerait : {@code planned} à venir, {@code missedToday} pour un envoi du jour dont
     * l'heure est déjà passée (marqué manqué, jamais rattrapé).
     */
    public record Sends(int planned, LocalDate first, LocalDate last, int missedToday) {
    }

    /** Effet d'une modification sur les envois futurs d'une relance déjà activée. */
    public record SendImpact(int added, int moved, int cancelled) {
    }

    /**
     * {@code changes} et {@code sendImpact} : seulement pour une ligne modifiée ({@code sendImpact} si elle est
     * activée) ; {@code closing} : la modification clôt le dossier ; {@code conflicts} : champs modifiés à l'écran
     * pour lesquels le fichier propose une autre valeur, à trancher un par un avant d'appliquer.
     */
    public record RowPreview(int rowNumber, String id, Action action, List<String> errors, List<String> warnings,
                             Interpreted interpreted, Sends sends, List<FieldChanges.Change> changes,
                             SendImpact sendImpact, boolean closing, List<String> conflicts) {
    }

    /** Relance en base, active, absente du fichier : aucune action par défaut (option « clore ces dossiers »). */
    public record AbsentRule(long ruleId, String businessId, String subject) {
    }

    /** {@code withConflicts} : lignes modifiées avec au moins un conflit à trancher. */
    public record Counts(int toCreate, int toUpdate, int unchanged, int rejected, int withWarnings,
                         int plannedSends, int sendsAdded, int sendsMoved, int sendsCancelled, int withConflicts) {
    }

    /** {@code missingColumns} non vide : tout le fichier est refusé, {@code rows} est vide. */
    public record ImportPreview(String fileName, List<String> missingColumns, Counts counts, List<RowPreview> rows,
                                List<AbsentRule> absent) {
    }

    /** Aperçu, et pour chaque ligne valide (par numéro de ligne) la règle typée et son empreinte : de quoi appliquer. */
    record Analysis(ImportPreview preview, Map<Integer, ImportedRule> rules, Map<Integer, String> hashes,
                    Map<Integer, Map<String, String>> variables, Map<Integer, Update> updates) {
    }

    /**
     * Ligne modifiée d'une relance existante : cellules du fichier, champs dont la valeur saisie à l'écran est gardée
     * d'office (le fichier n'y propose rien de nouveau), conflits, et valeur du fichier de chaque champ modifié.
     */
    record Update(Map<String, String> cells, Set<String> keptByDefault, List<String> conflicts,
                  Map<String, String> fileValues) {
    }

    /** Configuration du fichier où certains champs gardent la valeur de l'application ; {@code errors} si incohérente. */
    record Merged(ImportedRule rule, Map<String, String> values, List<String> errors) {
    }

    private final HolidayService holidayService;
    private final RuleRepository rules;
    private final OccurrenceRepository occurrences;
    private final Clock clock;

    public ImportPreviewService(HolidayService holidayService, RuleRepository rules, OccurrenceRepository occurrences,
            Clock clock) {
        this.holidayService = holidayService;
        this.rules = rules;
        this.occurrences = occurrences;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public ImportPreview preview(long ownerId, String fileName, InputStream content) {
        return analyse(ownerId, fileName, content).preview();
    }

    /** Même analyse pour l'aperçu et pour la confirmation : on applique exactement ce que l'aperçu montrait. */
    Analysis analyse(long ownerId, String fileName, InputStream content) {
        XlsxReader.ParsedSheet sheet;
        try {
            sheet = XlsxReader.read(content);
        } catch (XlsxReader.UnreadableFileException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage(), e);
        }
        BusinessCalendar calendar = holidayService.businessCalendar();
        LocalDate today = LocalDate.now(clock);
        FileValidator.FileResult result = new FileValidator(calendar).validate(sheet, today);
        if (result.isRejected()) {
            return new Analysis(new ImportPreview(fileName, result.missingColumns(),
                    new Counts(0, 0, 0, 0, 0, 0, 0, 0, 0, 0), List.of(), List.of()), Map.of(), Map.of(), Map.of(),
                    Map.of());
        }

        Map<Integer, Map<String, String>> cellsByRow = sheet.rows().stream()
                .collect(Collectors.toMap(XlsxReader.SheetRow::rowNumber, XlsxReader.SheetRow::cells));
        Map<String, Rule> existing = rules.findAllByOwnerIdOrderByBusinessIdAsc(ownerId).stream()
                .collect(Collectors.toMap(Rule::getBusinessId, Function.identity()));
        ScheduleCalculator calculator = new ScheduleCalculator(calendar);
        Instant now = clock.instant();

        Map<Integer, ImportedRule> validRules = new HashMap<>();
        Map<Integer, String> hashes = new HashMap<>();
        Map<Integer, Map<String, String>> variables = new HashMap<>();
        Map<Integer, Update> updates = new HashMap<>();
        List<RowPreview> rows = result.rows().stream().map(row -> {
            if (!row.isValid()) {
                return new RowPreview(row.rowNumber(), row.businessId(), Action.REJECTED, row.errors(),
                        row.warnings(), null, null, List.of(), null, false, List.of());
            }
            ImportedRule imported = row.rule();
            String hash = ConfigHash.of(cellsByRow.get(row.rowNumber()));
            Map<String, String> values = MessageTemplate.valuesUsed(cellsByRow.get(row.rowNumber()),
                    imported.subject(), imported.body());
            validRules.put(row.rowNumber(), imported);
            hashes.put(row.rowNumber(), hash);
            variables.put(row.rowNumber(), values);
            Rule current = existing.get(row.businessId());
            if (current == null) {
                return new RowPreview(row.rowNumber(), row.businessId(), Action.CREATE, row.errors(), row.warnings(),
                        Interpreted.of(imported), sends(calculator, imported), List.of(), null, false, List.of());
            }
            Map<String, String> edited = current.getEditedInApp();
            List<FieldChanges.Change> all = FieldChanges.between(current, imported, FieldChanges.freeVariables(values));
            // Champ modifié à l'écran que le fichier n'a pas changé depuis : la valeur de l'application reste.
            Set<String> keptByDefault = all.stream()
                    .filter(c -> edited.containsKey(c.field())
                            && Objects.equals(blankToNull(edited.get(c.field())), c.after()))
                    .map(FieldChanges.Change::field).collect(Collectors.toSet());
            List<FieldChanges.Change> changes = all.stream().filter(c -> !keptByDefault.contains(c.field())).toList();
            if (changes.isEmpty()) {
                return new RowPreview(row.rowNumber(), row.businessId(), Action.UNCHANGED, row.errors(),
                        row.warnings(), Interpreted.of(imported), null, List.of(), null, false, List.of());
            }
            List<String> conflicts = changes.stream().map(FieldChanges.Change::field).filter(edited::containsKey)
                    .toList();
            Map<String, String> fileValues = new HashMap<>();
            all.forEach(c -> fileValues.put(c.field(), c.after() == null ? "" : c.after()));
            Map<String, String> cells = cellsByRow.get(row.rowNumber());
            Merged merged = merge(current, cells, keptByDefault, calendar, today);
            if (!merged.errors().isEmpty()) {
                return new RowPreview(row.rowNumber(), row.businessId(), Action.REJECTED, merged.errors(),
                        row.warnings(), null, null, List.of(), null, false, List.of());
            }
            imported = merged.rule();
            validRules.put(row.rowNumber(), imported);
            variables.put(row.rowNumber(), merged.values());
            updates.put(row.rowNumber(), new Update(cells, keptByDefault, conflicts, fileValues));
            boolean closing = current.getStatus() == RuleStatus.ACTIVE && imported.status() == RuleStatus.CLOSED;
            List<String> warnings = new ArrayList<>(row.warnings());
            if (closing) {
                warnings.add("statut : le dossier sera clos, ses envois à venir annulés");
            }
            if (current.isDailyValidation() && !imported.dailyValidation() && imported.status() == RuleStatus.ACTIVE) {
                warnings.add("validation_quotidienne : passe de oui à non, les envois partiront sans validation");
            }
            SendImpact impact = null;
            if (current.isActivated() && current.getStatus() == RuleStatus.ACTIVE) {
                SendsDiff diff = impact(current, imported, calculator, now);
                impact = new SendImpact(diff.added(), diff.moved(), diff.cancelled());
            }
            return new RowPreview(row.rowNumber(), row.businessId(), Action.UPDATE, row.errors(), List.copyOf(warnings),
                    Interpreted.of(imported), sends(calculator, imported), changes, impact, closing, conflicts);
        }).toList();

        Set<String> inFile = result.rows().stream().map(FileValidator.RowResult::businessId).collect(Collectors.toSet());
        List<AbsentRule> absent = existing.values().stream()
                .filter(rule -> rule.getStatus() == RuleStatus.ACTIVE && !inFile.contains(rule.getBusinessId()))
                .sorted((a, b) -> a.getBusinessId().compareTo(b.getBusinessId()))
                .map(rule -> new AbsentRule(rule.getId(), rule.getBusinessId(), rule.getSubject()))
                .toList();

        return new Analysis(new ImportPreview(fileName, List.of(), count(rows), rows, absent), validRules, hashes,
                variables, updates);
    }

    /**
     * Configuration du fichier dont les champs {@code keepApp} reprennent la valeur de l'application, revalidée comme
     * une ligne du fichier (un mélange peut être incohérent, par exemple une date de fin avant la date de début).
     */
    static Merged merge(Rule current, Map<String, String> fileCells, Set<String> keepApp, BusinessCalendar calendar,
            LocalDate today) {
        Map<String, String> cells = new HashMap<>(fileCells);
        Map<String, String> app = RuleCells.of(current);
        keepApp.forEach(field -> cells.put(field, app.getOrDefault(field, "")));
        RowValidation validation = new RowValidator(calendar).validate(cells, today);
        if (!validation.errors().isEmpty()) {
            return new Merged(null, Map.of(), validation.errors());
        }
        ImportedRule rule = validation.rule();
        return new Merged(rule, MessageTemplate.valuesUsed(cells, rule.subject(), rule.body()), List.of());
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    /** Effet sur les envois d'une relance activée si on applique la ligne du fichier (rien n'est écrit). */
    private SendsDiff impact(Rule current, ImportedRule imported, ScheduleCalculator calculator, Instant now) {
        var existing = occurrences.findAllByRuleIdOrderByScheduledDateAsc(current.getId());
        if (imported.status() == RuleStatus.CLOSED) {
            return new SendsDiff(existing.stream().filter(o -> OccurrenceState.NOT_STARTED.contains(o.getState()))
                    .toList(), List.of(), List.of(), List.of());
        }
        OccurrenceState upcoming = imported.dailyValidation()
                ? OccurrenceState.AWAITING_VALIDATION : OccurrenceState.PLANNED;
        return SendsDiff.compute(existing, calculator.sendDates(imported.scheduling()),
                day -> day.atTime(imported.sendTime()).atZone(clock.getZone()).toInstant(), upcoming, now);
    }

    private Sends sends(ScheduleCalculator calculator, ImportedRule rule) {
        if (rule.status() != RuleStatus.ACTIVE) {
            return new Sends(0, null, null, 0);
        }
        ActivationPlanner.Split split = ActivationPlanner.split(calculator.sendDates(rule.scheduling()),
                rule.sendTime(), clock.getZone(), clock.instant());
        List<LocalDate> planned = split.planned();
        return new Sends(planned.size(), planned.isEmpty() ? null : planned.getFirst(),
                planned.isEmpty() ? null : planned.getLast(), split.missed().size());
    }

    private static Counts count(List<RowPreview> rows) {
        return new Counts(
                (int) rows.stream().filter(r -> r.action() == Action.CREATE).count(),
                (int) rows.stream().filter(r -> r.action() == Action.UPDATE).count(),
                (int) rows.stream().filter(r -> r.action() == Action.UNCHANGED).count(),
                (int) rows.stream().filter(r -> r.action() == Action.REJECTED).count(),
                (int) rows.stream().filter(r -> !r.warnings().isEmpty()).count(),
                rows.stream().filter(r -> r.sends() != null && r.action() == Action.CREATE)
                        .mapToInt(r -> r.sends().planned()).sum(),
                rows.stream().filter(r -> r.sendImpact() != null).mapToInt(r -> r.sendImpact().added()).sum(),
                rows.stream().filter(r -> r.sendImpact() != null).mapToInt(r -> r.sendImpact().moved()).sum(),
                rows.stream().filter(r -> r.sendImpact() != null).mapToInt(r -> r.sendImpact().cancelled()).sum(),
                (int) rows.stream().filter(r -> !r.conflicts().isEmpty()).count());
    }
}
