package com.orpex.core.importing;

import com.orpex.core.calendar.BusinessCalendar;
import com.orpex.core.calendar.SchedulingRule;
import com.orpex.core.calendar.SendMode;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Valide une ligne du fichier Excel avant toute écriture en base (spécification, section 5).
 *
 * <p>Les cellules sont fournies sous forme de texte, avec des clés en minuscules (les en-têtes du fichier).
 * Les dates doivent déjà être au format ISO (AAAA-MM-JJ) : la conversion des cellules de type date Excel
 * relève du lecteur de fichier.
 */
public final class RowValidator {

    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");
    private static final Pattern TIME_PATTERN = Pattern.compile("^([01]\\d|2[0-3]):[0-5]\\d$");
    private static final Pattern VARIABLE_PATTERN = Pattern.compile("\\{\\{\\s*([a-z0-9_]+)\\s*}}");

    private final BusinessCalendar calendar;

    public RowValidator(BusinessCalendar calendar) {
        this.calendar = calendar;
    }

    /** Colonnes obligatoires absentes des en-têtes : si la liste n'est pas vide, tout le fichier est refusé. */
    public static List<String> missingColumns(Collection<String> headers) {
        List<String> normalized = headers.stream().map(h -> h.trim().toLowerCase(Locale.ROOT)).toList();
        return Columns.REQUIRED.stream().filter(c -> !normalized.contains(c)).toList();
    }

    public RowValidation validate(Map<String, String> cells, LocalDate today) {
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        String id = value(cells, Columns.ID);
        if (id.isEmpty()) {
            errors.add("id : valeur obligatoire");
        }

        String email = value(cells, Columns.EMAIL);
        if (email.isEmpty()) {
            errors.add("email : valeur obligatoire");
        } else if (!EMAIL_PATTERN.matcher(email).matches()) {
            errors.add("email : adresse invalide (« " + email + " »)");
        }

        String subject = value(cells, Columns.SUBJECT);
        String body = value(cells, Columns.BODY);
        if (subject.isEmpty()) {
            errors.add("objet : valeur obligatoire");
        }
        if (body.isEmpty()) {
            errors.add("contenu : valeur obligatoire");
        }
        checkVariables(Columns.SUBJECT, subject, cells, errors);
        checkVariables(Columns.BODY, body, cells, errors);

        LocalTime sendTime = parseTime(cells, errors);
        Boolean businessDaysOnly = parseYesNo(cells, Columns.BUSINESS_DAYS_ONLY, errors);
        Boolean dailyValidation = parseYesNo(cells, Columns.DAILY_VALIDATION, errors);
        RuleStatus status = parseStatus(cells, errors);
        SchedulingRule scheduling = parseScheduling(cells, businessDaysOnly, today, errors, warnings);

        if (!value(cells, Columns.VALIDATOR).isEmpty()) {
            warnings.add("validateur : colonne ignorée en version 1");
        }

        if (!errors.isEmpty()) {
            return new RowValidation(List.copyOf(errors), List.copyOf(warnings), null);
        }

        ImportedRule rule = new ImportedRule(
                id, email,
                value(cells, Columns.FIRST_NAME), value(cells, Columns.LAST_NAME),
                value(cells, Columns.CASE_REFERENCE),
                subject, body, scheduling, sendTime, dailyValidation, status);
        return new RowValidation(List.of(), List.copyOf(warnings), rule);
    }

    private SchedulingRule parseScheduling(Map<String, String> cells, Boolean businessDaysOnly, LocalDate today,
                                           List<String> errors, List<String> warnings) {
        String rawMode = value(cells, Columns.SEND_MODE);
        if (rawMode.isEmpty()) {
            errors.add("mode_envoi : valeur obligatoire (ponctuel, avant_echeance ou periode)");
            return null;
        }
        SendMode mode;
        try {
            mode = SendMode.fromFileValue(rawMode);
        } catch (IllegalArgumentException e) {
            errors.add("mode_envoi : « " + rawMode + " » inconnu (attendu : ponctuel, avant_echeance ou periode)");
            return null;
        }

        boolean only = Boolean.TRUE.equals(businessDaysOnly);
        switch (mode) {
            case PONCTUEL -> {
                LocalDate sendDate = requiredDate(cells, Columns.SEND_DATE, errors);
                if (sendDate == null) {
                    return null;
                }
                if (sendDate.isBefore(today)) {
                    warnings.add("date_envoi : date passée, aucun envoi ne sera créé pour ce jour");
                }
                return SchedulingRule.once(sendDate, only);
            }
            case AVANT_ECHEANCE -> {
                LocalDate due = requiredDate(cells, Columns.DUE_DATE, errors);
                Integer daysBefore = requiredNonNegativeInt(cells, Columns.DAYS_BEFORE, errors);
                if (due == null || daysBefore == null) {
                    return null;
                }
                if (only && !calendar.isBusinessDay(due)) {
                    warnings.add("date_echeance : jour non ouvré, le dernier envoi sera reporté au dernier jour ouvré précédent");
                }
                if (due.minusDays(daysBefore).isBefore(today)) {
                    warnings.add("les envois commencent aujourd'hui : les jours passés ne sont pas rattrapés");
                }
                return SchedulingRule.beforeDue(due, daysBefore, only);
            }
            case PERIODE -> {
                LocalDate start = requiredDate(cells, Columns.START_DATE, errors);
                LocalDate end = requiredDate(cells, Columns.END_DATE, errors);
                if (start == null || end == null) {
                    return null;
                }
                if (end.isBefore(start)) {
                    errors.add("date_fin : doit être égale ou postérieure à date_debut");
                    return null;
                }
                if (start.isBefore(today)) {
                    warnings.add("date_debut : date passée, les envois commencent aujourd'hui");
                }
                return SchedulingRule.period(start, end, only);
            }
        }
        return null;
    }

    private static void checkVariables(String column, String text, Map<String, String> cells, List<String> errors) {
        Matcher matcher = VARIABLE_PATTERN.matcher(text);
        while (matcher.find()) {
            String name = matcher.group(1);
            if (value(cells, name).isEmpty()) {
                errors.add(column + " : la variable {{" + name + "}} ne correspond à aucune colonne remplie");
            }
        }
    }

    private static LocalTime parseTime(Map<String, String> cells, List<String> errors) {
        String raw = value(cells, Columns.SEND_TIME);
        if (raw.isEmpty()) {
            errors.add("heure_envoi : valeur obligatoire (format HH:MM)");
            return null;
        }
        if (!TIME_PATTERN.matcher(raw).matches()) {
            errors.add("heure_envoi : « " + raw + " » invalide (format HH:MM attendu)");
            return null;
        }
        return LocalTime.parse(raw);
    }

    private static Boolean parseYesNo(Map<String, String> cells, String column, List<String> errors) {
        String raw = value(cells, column).toLowerCase(Locale.ROOT);
        return switch (raw) {
            case "oui" -> Boolean.TRUE;
            case "non" -> Boolean.FALSE;
            case "" -> {
                errors.add(column + " : valeur obligatoire (oui ou non, jamais vide)");
                yield null;
            }
            default -> {
                errors.add(column + " : « " + raw + " » invalide (attendu exactement : oui ou non)");
                yield null;
            }
        };
    }

    private static RuleStatus parseStatus(Map<String, String> cells, List<String> errors) {
        String raw = value(cells, Columns.STATUS).toLowerCase(Locale.ROOT);
        return switch (raw) {
            case "", "actif" -> RuleStatus.ACTIVE;
            case "clos" -> RuleStatus.CLOSED;
            default -> {
                errors.add("statut : « " + raw + " » invalide (attendu : actif ou clos)");
                yield null;
            }
        };
    }

    private static LocalDate requiredDate(Map<String, String> cells, String column, List<String> errors) {
        String raw = value(cells, column);
        if (raw.isEmpty()) {
            errors.add(column + " : valeur obligatoire pour ce mode d'envoi");
            return null;
        }
        try {
            return LocalDate.parse(raw);
        } catch (DateTimeParseException e) {
            errors.add(column + " : date « " + raw + " » illisible (format AAAA-MM-JJ attendu)");
            return null;
        }
    }

    private static Integer requiredNonNegativeInt(Map<String, String> cells, String column, List<String> errors) {
        String raw = value(cells, column);
        if (raw.isEmpty()) {
            errors.add(column + " : valeur obligatoire pour ce mode d'envoi");
            return null;
        }
        try {
            int parsed = Integer.parseInt(raw);
            if (parsed < 0) {
                errors.add(column + " : doit être supérieur ou égal à 0");
                return null;
            }
            return parsed;
        } catch (NumberFormatException e) {
            errors.add(column + " : « " + raw + " » n'est pas un entier");
            return null;
        }
    }

    private static String value(Map<String, String> cells, String column) {
        String raw = cells.get(column);
        return raw == null ? "" : raw.trim();
    }
}
