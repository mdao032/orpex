package com.orpex.importing;

import com.orpex.core.calendar.ScheduleCalculator;
import com.orpex.core.calendar.BusinessCalendar;
import com.orpex.importing.ImportPreviewService.AbsentRule;
import com.orpex.importing.ImportPreviewService.Action;
import com.orpex.importing.ImportPreviewService.Merged;
import com.orpex.importing.ImportPreviewService.Update;
import com.orpex.importing.ImportPreviewService.Analysis;
import com.orpex.importing.ImportPreviewService.RowPreview;
import com.orpex.holiday.HolidayService;
import com.orpex.rule.ClosureService;
import com.orpex.rule.ImportRecord;
import com.orpex.rule.ImportRecordRepository;
import com.orpex.rule.RescheduleService;
import com.orpex.rule.Rule;
import com.orpex.rule.RuleRepository;
import com.orpex.rule.SendsDiff;
import java.io.InputStream;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Confirmation d'un import (spécification §9) : le fichier est réanalysé exactement comme pour l'aperçu, puis appliqué
 * en une seule transaction : lignes nouvelles créées, lignes modifiées mises à jour (envois futurs des lignes activées
 * recalculés, dossier clos si le fichier dit « clos »), lignes inchangées ignorées. Rien n'est supprimé. Les lignes
 * absentes du fichier ne changent pas, sauf si l'utilisateur a demandé de clore ces dossiers.
 */
@Service
public class ImportApplyService {

    private static final Logger log = LoggerFactory.getLogger(ImportApplyService.class);

    public record ImportResult(long importId, String fileName, int created, int updated, int unchanged, int rejected,
                               int closedAbsent, int sendsAdded, int sendsMoved, int sendsCancelled) {
    }

    private final ImportPreviewService analyser;
    private final RuleRepository rules;
    private final ImportRecordRepository imports;
    private final RescheduleService rescheduler;
    private final ClosureService closure;
    private final HolidayService holidayService;
    private final Clock clock;

    public ImportApplyService(ImportPreviewService analyser, RuleRepository rules, ImportRecordRepository imports,
            RescheduleService rescheduler, ClosureService closure, HolidayService holidayService, Clock clock) {
        this.analyser = analyser;
        this.rules = rules;
        this.imports = imports;
        this.rescheduler = rescheduler;
        this.closure = closure;
        this.holidayService = holidayService;
        this.clock = clock;
    }

    /** Choix pour un conflit : garder la valeur de l'application ou prendre celle du fichier. */
    public enum Choice { APP, FILE }

    /**
     * {@code resolutions} : pour chaque id de ligne en conflit, le choix de chaque champ (tous obligatoires : jamais
     * d'écrasement silencieux, spécification §9).
     */
    @Transactional
    public ImportResult apply(long ownerId, String fileName, InputStream content, boolean closeAbsent,
            Map<String, Map<String, Choice>> resolutions) {
        Analysis analysis = analyser.analyse(ownerId, fileName, content);
        if (!analysis.preview().missingColumns().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Le fichier ne peut pas être importé : "
                    + "colonnes obligatoires absentes (" + String.join(", ", analysis.preview().missingColumns()) + ").");
        }

        List<String> unresolved = analysis.preview().rows().stream()
                .filter(row -> row.action() == Action.UPDATE && !row.conflicts().isEmpty())
                .flatMap(row -> row.conflicts().stream()
                        .filter(field -> resolutions.getOrDefault(row.id(), Map.of()).get(field) == null)
                        .map(field -> row.id() + " (" + field + ")"))
                .toList();
        if (!unresolved.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Conflits non tranchés : " + String.join(", ",
                    unresolved) + ". Choisissez pour chacun la valeur à garder, puis confirmez à nouveau.");
        }

        Instant now = clock.instant();
        BusinessCalendar calendar = holidayService.businessCalendar();
        LocalDate today = LocalDate.now(clock);
        ScheduleCalculator calculator = new ScheduleCalculator(calendar);
        int created = 0;
        int updated = 0;
        int unchanged = 0;
        int rejected = 0;
        int added = 0;
        int moved = 0;
        int cancelled = 0;
        List<String> updatedIds = new ArrayList<>();
        for (RowPreview row : analysis.preview().rows()) {
            switch (row.action()) {
                case CREATE -> {
                    rules.save(Rule.fromImport(analysis.rules().get(row.rowNumber()),
                            analysis.hashes().get(row.rowNumber()), analysis.variables().get(row.rowNumber()),
                            ownerId, now));
                    created++;
                }
                case UPDATE -> {
                    Rule rule = rules.findByOwnerIdAndBusinessId(ownerId, row.id()).orElseThrow();
                    rule = rules.findForUpdateByIdAndOwnerId(rule.getId(), ownerId).orElseThrow();
                    Update update = analysis.updates().get(row.rowNumber());
                    Map<String, Choice> choices = resolutions.getOrDefault(row.id(), Map.of());
                    Set<String> keepApp = new HashSet<>(update.keptByDefault());
                    update.conflicts().stream().filter(field -> choices.get(field) == Choice.APP).forEach(keepApp::add);
                    Merged merged = ImportPreviewService.merge(rule, update.cells(), keepApp, calendar, today);
                    if (!merged.errors().isEmpty()) {
                        throw new ResponseStatusException(HttpStatus.CONFLICT, "Ligne " + row.rowNumber() + " ("
                                + row.id() + ") : ces choix donnent une relance incohérente : "
                                + String.join(" ; ", merged.errors()) + ".");
                    }
                    // Champs où l'application l'emporte : on retient la valeur du fichier écartée.
                    Map<String, String> stillEdited = new LinkedHashMap<>();
                    keepApp.forEach(field -> stillEdited.put(field, update.fileValues().getOrDefault(field, "")));
                    rule.apply(merged.rule(), analysis.hashes().get(row.rowNumber()), stillEdited, now);
                    rule.replaceVariables(merged.values());
                    if (rule.isActivated()) {
                        SendsDiff diff = rescheduler.diff(rule, calculator, now);
                        rescheduler.apply(rule, diff, now);
                        added += diff.added();
                        moved += diff.moved();
                        cancelled += diff.cancelled();
                    }
                    updated++;
                    updatedIds.add(row.id());
                }
                case UNCHANGED -> unchanged++;
                case REJECTED -> rejected++;
            }
        }

        int closedAbsent = 0;
        if (closeAbsent) {
            for (AbsentRule absent : analysis.preview().absent()) {
                cancelled += closure.close(ownerId, absent.ruleId()).cancelled();
                closedAbsent++;
            }
        }

        String summary = summary(updatedIds, closedAbsent);
        ImportRecord record;
        try {
            record = imports.save(new ImportRecord(ownerId, fileName, created, updated, unchanged, rejected, summary,
                    now));
            rules.flush();
        } catch (DataIntegrityViolationException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Un autre import des mêmes lignes a eu lieu entre-temps : relancez l'aperçu.", e);
        }
        // Traçabilité (spécification §9) ; détail par champ : journal d'audit, phase 8.
        log.info("Import « {} » par l'utilisateur {} : {} créée(s), {} modifiée(s) {}, {} inchangée(s), {} rejetée(s), "
                + "{} dossier(s) absent(s) clos ; envois : +{}, ~{}, -{}", fileName, ownerId, created, updated,
                updatedIds, unchanged, rejected, closedAbsent, added, moved, cancelled);
        return new ImportResult(record.getId(), fileName, created, updated, unchanged, rejected, closedAbsent, added,
                moved, cancelled);
    }

    private static String summary(List<String> updatedIds, int closedAbsent) {
        List<String> parts = new ArrayList<>();
        if (!updatedIds.isEmpty()) {
            parts.add("Lignes modifiées : " + String.join(", ", updatedIds));
        }
        if (closedAbsent > 0) {
            parts.add(closedAbsent + " dossier(s) absent(s) du fichier clos");
        }
        return parts.isEmpty() ? null : String.join(" ; ", parts);
    }
}
