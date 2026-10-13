package com.orpex.importing;

import com.orpex.core.calendar.ActivationPlanner;
import com.orpex.core.calendar.BusinessCalendar;
import com.orpex.core.calendar.ScheduleCalculator;
import com.orpex.core.calendar.SchedulingRule;
import com.orpex.core.calendar.SendMode;
import com.orpex.core.importing.ConfigHash;
import com.orpex.core.importing.FileValidator;
import com.orpex.core.importing.ImportedRule;
import com.orpex.core.importing.RuleStatus;
import com.orpex.core.importing.XlsxReader;
import com.orpex.holiday.HolidayService;
import com.orpex.rule.Rule;
import com.orpex.rule.RuleRepository;
import java.io.InputStream;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Aperçu d'un import (spécification §5 et §9) : lit et valide le fichier, compare chaque ligne à la base, calcule les
 * envois prévus. N'écrit rien : l'utilisateur confirme ensuite en renvoyant le même fichier.
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

    public record RowPreview(int rowNumber, String id, Action action, List<String> errors, List<String> warnings,
                             Interpreted interpreted, Sends sends) {
    }

    public record Counts(int toCreate, int toUpdate, int unchanged, int rejected, int withWarnings,
                         int plannedSends) {
    }

    /** {@code missingColumns} non vide : tout le fichier est refusé, {@code rows} est vide. */
    public record ImportPreview(String fileName, List<String> missingColumns, Counts counts, List<RowPreview> rows) {
    }

    /** Aperçu, et pour chaque ligne valide (par numéro de ligne) la règle typée et son empreinte : de quoi appliquer. */
    record Analysis(ImportPreview preview, Map<Integer, ImportedRule> rules, Map<Integer, String> hashes) {
    }

    private final HolidayService holidayService;
    private final RuleRepository rules;
    private final Clock clock;

    public ImportPreviewService(HolidayService holidayService, RuleRepository rules, Clock clock) {
        this.holidayService = holidayService;
        this.rules = rules;
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
            return new Analysis(new ImportPreview(fileName, result.missingColumns(), new Counts(0, 0, 0, 0, 0, 0),
                    List.of()), Map.of(), Map.of());
        }

        Map<Integer, Map<String, String>> cellsByRow = sheet.rows().stream()
                .collect(Collectors.toMap(XlsxReader.SheetRow::rowNumber, XlsxReader.SheetRow::cells));
        List<String> ids = result.rows().stream().map(FileValidator.RowResult::businessId)
                .filter(id -> !id.isEmpty()).toList();
        Map<String, Rule> existing = rules.findAllByOwnerIdAndBusinessIdIn(ownerId, ids).stream()
                .collect(Collectors.toMap(Rule::getBusinessId, Function.identity()));
        ScheduleCalculator calculator = new ScheduleCalculator(calendar);

        Map<Integer, ImportedRule> validRules = new HashMap<>();
        Map<Integer, String> hashes = new HashMap<>();
        List<RowPreview> rows = result.rows().stream().map(row -> {
            if (!row.isValid()) {
                return new RowPreview(row.rowNumber(), row.businessId(), Action.REJECTED, row.errors(),
                        row.warnings(), null, null);
            }
            String hash = ConfigHash.of(cellsByRow.get(row.rowNumber()));
            validRules.put(row.rowNumber(), row.rule());
            hashes.put(row.rowNumber(), hash);
            Rule current = existing.get(row.businessId());
            Action action = current == null ? Action.CREATE
                    : current.getConfigHash().equals(hash) ? Action.UNCHANGED : Action.UPDATE;
            return new RowPreview(row.rowNumber(), row.businessId(), action, row.errors(), row.warnings(),
                    Interpreted.of(row.rule()), sends(calculator, row.rule()));
        }).toList();

        return new Analysis(new ImportPreview(fileName, List.of(), count(rows), rows), validRules, hashes);
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
                rows.stream().filter(r -> r.sends() != null && r.action() != Action.UNCHANGED)
                        .mapToInt(r -> r.sends().planned()).sum());
    }
}
