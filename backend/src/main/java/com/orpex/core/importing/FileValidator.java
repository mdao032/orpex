package com.orpex.core.importing;

import com.orpex.core.calendar.BusinessCalendar;
import com.orpex.core.calendar.ScheduleCalculator;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Valide un fichier entier (spécification §5) : colonnes obligatoires, chaque ligne avec {@link RowValidator}, puis
 * les contrôles qui portent sur plusieurs lignes ou sur le calendrier calculé.
 *
 * <ul>
 *   <li>bloquant : {@code id} présent plusieurs fois ; aucun envoi à venir (toutes les dates calculées passées) ;</li>
 *   <li>avertissement : même destinataire sur plusieurs lignes avec des envois le même jour.</li>
 * </ul>
 */
public final class FileValidator {

    /** Résultat d'une ligne ; {@code rule} n'est renseignée que si {@code errors} est vide. */
    public record RowResult(int rowNumber, String businessId, List<String> errors, List<String> warnings,
                            ImportedRule rule) {

        public boolean isValid() {
            return errors.isEmpty();
        }
    }

    /** Si {@code missingColumns} n'est pas vide, tout le fichier est refusé et {@code rows} est vide. */
    public record FileResult(List<String> missingColumns, List<RowResult> rows) {

        public boolean isRejected() {
            return !missingColumns.isEmpty();
        }

        public long validCount() {
            return rows.stream().filter(RowResult::isValid).count();
        }

        public long errorCount() {
            return rows.size() - validCount();
        }
    }

    private final RowValidator rowValidator;
    private final ScheduleCalculator calculator;

    public FileValidator(BusinessCalendar calendar) {
        this.rowValidator = new RowValidator(calendar);
        this.calculator = new ScheduleCalculator(calendar);
    }

    public FileResult validate(XlsxReader.ParsedSheet sheet, LocalDate today) {
        List<String> missing = RowValidator.missingColumns(sheet.headers());
        if (!missing.isEmpty()) {
            return new FileResult(missing, List.of());
        }

        List<Draft> drafts = new ArrayList<>();
        for (XlsxReader.SheetRow row : sheet.rows()) {
            RowValidation validation = rowValidator.validate(row.cells(), today);
            String id = row.cells().getOrDefault(Columns.ID, "").strip();
            drafts.add(new Draft(row.rowNumber(), id, new ArrayList<>(validation.errors()),
                    new ArrayList<>(validation.warnings()), validation.rule()));
        }

        flagDuplicateIds(drafts);
        Map<Draft, Set<LocalDate>> upcoming = flagRowsWithoutUpcomingSend(drafts, today);
        flagSameRecipientOnTheSameDay(upcoming);

        return new FileResult(List.of(), drafts.stream().map(Draft::toResult).toList());
    }

    private static void flagDuplicateIds(List<Draft> drafts) {
        Map<String, List<Draft>> byId = drafts.stream()
                .filter(d -> !d.id.isEmpty())
                .collect(Collectors.groupingBy(d -> d.id, LinkedHashMap::new, Collectors.toList()));
        for (var entry : byId.entrySet()) {
            if (entry.getValue().size() > 1) {
                String lines = entry.getValue().stream().map(d -> String.valueOf(d.rowNumber))
                        .collect(Collectors.joining(", "));
                entry.getValue().forEach(d -> d.errors.add(
                        "id : « " + entry.getKey() + " » présent plusieurs fois dans le fichier (lignes " + lines + ")"));
            }
        }
    }

    /** Jours d'envoi à venir de chaque ligne valide et active ; une ligne active sans aucun envoi à venir est refusée. */
    private Map<Draft, Set<LocalDate>> flagRowsWithoutUpcomingSend(List<Draft> drafts, LocalDate today) {
        Map<Draft, Set<LocalDate>> upcoming = new LinkedHashMap<>();
        for (Draft draft : drafts) {
            if (!draft.errors.isEmpty() || draft.rule == null || draft.rule.status() != RuleStatus.ACTIVE) {
                continue;
            }
            Set<LocalDate> days = calculator.sendDates(draft.rule.scheduling()).stream()
                    .filter(day -> !day.isBefore(today))
                    .collect(Collectors.toCollection(HashSet::new));
            if (days.isEmpty()) {
                draft.errors.add("calendrier : aucun envoi à venir, toutes les dates calculées sont passées");
            } else {
                upcoming.put(draft, days);
            }
        }
        return upcoming;
    }

    private static void flagSameRecipientOnTheSameDay(Map<Draft, Set<LocalDate>> upcoming) {
        Map<String, List<Draft>> byEmail = upcoming.keySet().stream().collect(Collectors.groupingBy(
                d -> d.rule.recipientEmail().toLowerCase(Locale.ROOT), LinkedHashMap::new, Collectors.toList()));
        for (List<Draft> sameRecipient : byEmail.values()) {
            for (Draft draft : sameRecipient) {
                List<String> overlapping = sameRecipient.stream()
                        .filter(other -> other != draft)
                        .filter(other -> upcoming.get(other).stream().anyMatch(upcoming.get(draft)::contains))
                        .map(other -> String.valueOf(other.rowNumber))
                        .toList();
                if (!overlapping.isEmpty()) {
                    draft.warnings.add("email : même destinataire que la ligne " + String.join(", ", overlapping)
                            + ", avec des envois les mêmes jours (risque de doublon pour le destinataire)");
                }
            }
        }
    }

    /** Ligne en cours de validation : les contrôles entre lignes y ajoutent erreurs et avertissements. */
    private static final class Draft {
        final int rowNumber;
        final String id;
        final List<String> errors;
        final List<String> warnings;
        final ImportedRule rule;

        Draft(int rowNumber, String id, List<String> errors, List<String> warnings, ImportedRule rule) {
            this.rowNumber = rowNumber;
            this.id = id;
            this.errors = errors;
            this.warnings = warnings;
            this.rule = rule;
        }

        RowResult toResult() {
            return new RowResult(rowNumber, id, List.copyOf(errors), List.copyOf(warnings),
                    errors.isEmpty() ? rule : null);
        }
    }
}
