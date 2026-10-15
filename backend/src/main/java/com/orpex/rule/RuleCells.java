package com.orpex.rule;

import com.orpex.core.calendar.SchedulingRule;
import com.orpex.core.importing.Columns;
import com.orpex.core.importing.RuleStatus;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/** Configuration d'une relance écrite comme une ligne du fichier (dates AAAA-MM-JJ, heure HH:MM, oui/non). */
public final class RuleCells {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");

    private RuleCells() {
    }

    public static Map<String, String> of(Rule rule) {
        SchedulingRule s = rule.scheduling();
        Map<String, String> cells = new HashMap<>();
        cells.put(Columns.ID, rule.getBusinessId());
        cells.put(Columns.EMAIL, text(rule.getRecipientEmail()));
        cells.put(Columns.FIRST_NAME, text(rule.getFirstName()));
        cells.put(Columns.LAST_NAME, text(rule.getLastName()));
        cells.put(Columns.CASE_REFERENCE, text(rule.getCaseReference()));
        cells.put(Columns.SUBJECT, text(rule.getSubject()));
        cells.put(Columns.BODY, text(rule.getBody()));
        cells.put(Columns.SEND_MODE, s.mode().name().toLowerCase(Locale.ROOT));
        cells.put(Columns.SEND_DATE, date(s.sendDate()));
        cells.put(Columns.DUE_DATE, date(s.dueDate()));
        cells.put(Columns.DAYS_BEFORE, s.daysBefore() == null ? "" : s.daysBefore().toString());
        cells.put(Columns.START_DATE, date(s.startDate()));
        cells.put(Columns.END_DATE, date(s.endDate()));
        cells.put(Columns.SEND_TIME, TIME.format(rule.getSendTime()));
        cells.put(Columns.BUSINESS_DAYS_ONLY, s.businessDaysOnly() ? "oui" : "non");
        cells.put(Columns.DAILY_VALIDATION, rule.isDailyValidation() ? "oui" : "non");
        cells.put(Columns.STATUS, rule.getStatus() == RuleStatus.CLOSED ? "clos" : "actif");
        return cells;
    }

    private static String text(String value) {
        return value == null ? "" : value;
    }

    private static String date(LocalDate value) {
        return value == null ? "" : value.toString();
    }
}
