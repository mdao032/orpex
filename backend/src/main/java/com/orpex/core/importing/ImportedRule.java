package com.orpex.core.importing;

import com.orpex.core.calendar.SchedulingRule;
import java.time.LocalTime;

/** Une ligne du fichier, validée et typée. */
public record ImportedRule(
        String businessId,
        String recipientEmail,
        String firstName,
        String lastName,
        String caseReference,
        String subject,
        String body,
        SchedulingRule scheduling,
        LocalTime sendTime,
        boolean dailyValidation,
        RuleStatus status) {
}
