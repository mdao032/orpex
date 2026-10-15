package com.orpex.importing;

import com.orpex.core.calendar.SchedulingRule;
import com.orpex.core.calendar.SendMode;
import com.orpex.core.importing.ImportedRule;
import com.orpex.core.importing.RuleStatus;
import com.orpex.rule.Rule;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;

/** Champs modifiés entre une relance en base et la ligne du fichier, en clair pour l'aperçu (spécification §9). */
final class FieldChanges {

    /** {@code field} : nom de la colonne du fichier ; {@code before}/{@code after} : valeurs affichables. */
    public record Change(String field, String before, String after) {
    }

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");

    private FieldChanges() {
    }

    static List<Change> between(Rule current, ImportedRule imported, Map<String, String> newFreeVariables) {
        SchedulingRule before = current.scheduling();
        SchedulingRule after = imported.scheduling();
        List<Change> changes = new ArrayList<>();
        add(changes, "email", current.getRecipientEmail(), imported.recipientEmail());
        add(changes, "prenom", current.getFirstName(), imported.firstName());
        add(changes, "nom", current.getLastName(), imported.lastName());
        add(changes, "reference_dossier", current.getCaseReference(), imported.caseReference());
        add(changes, "objet", current.getSubject(), imported.subject());
        add(changes, "contenu", current.getBody(), imported.body());
        add(changes, "mode_envoi", mode(before.mode()), mode(after.mode()));
        add(changes, "date_envoi", date(before.sendDate()), date(after.sendDate()));
        add(changes, "date_echeance", date(before.dueDate()), date(after.dueDate()));
        add(changes, "jours_avant", number(before.daysBefore()), number(after.daysBefore()));
        add(changes, "date_debut", date(before.startDate()), date(after.startDate()));
        add(changes, "date_fin", date(before.endDate()), date(after.endDate()));
        add(changes, "heure_envoi", time(current.getSendTime()), time(imported.sendTime()));
        add(changes, "jours_ouvres_uniquement", yesNo(before.businessDaysOnly()), yesNo(after.businessDaysOnly()));
        add(changes, "validation_quotidienne", yesNo(current.isDailyValidation()), yesNo(imported.dailyValidation()));
        add(changes, "statut", status(current.getStatus()), status(imported.status()));
        Map<String, String> oldFree = ImportPreviewService.freeVariables(current.getVariables());
        for (String name : new TreeSet<>(newFreeVariables.keySet())) {
            add(changes, name, oldFree.get(name), newFreeVariables.get(name));
        }
        return changes;
    }

    private static void add(List<Change> changes, String field, String before, String after) {
        String b = blankToNull(before);
        String a = blankToNull(after);
        if (!Objects.equals(b, a)) {
            changes.add(new Change(field, b, a));
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static String mode(SendMode mode) {
        return mode == null ? null : switch (mode) {
            case PONCTUEL -> "ponctuel";
            case AVANT_ECHEANCE -> "avant_echeance";
            case PERIODE -> "periode";
        };
    }

    private static String date(LocalDate date) {
        return date == null ? null : DATE.format(date);
    }

    private static String time(LocalTime time) {
        return time == null ? null : TIME.format(time);
    }

    private static String number(Integer value) {
        return value == null ? null : value.toString();
    }

    private static String yesNo(boolean value) {
        return value ? "oui" : "non";
    }

    private static String status(RuleStatus status) {
        return status == RuleStatus.CLOSED ? "clos" : "actif";
    }
}
