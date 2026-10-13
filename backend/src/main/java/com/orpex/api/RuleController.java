package com.orpex.api;

import com.orpex.account.CurrentUser;
import com.orpex.core.calendar.SchedulingRule;
import com.orpex.core.calendar.SendMode;
import com.orpex.core.importing.RuleStatus;
import com.orpex.rule.Rule;
import com.orpex.rule.RuleRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Relances (lignes importées) de l'utilisateur connecté. */
@RestController
@RequestMapping("/api/rules")
public class RuleController {

    public record RuleView(long id, String businessId, String recipientEmail, String firstName, String lastName,
                           String caseReference, String subject, SendMode sendMode, LocalDate sendDate,
                           LocalDate dueDate, Integer daysBefore, LocalDate startDate, LocalDate endDate,
                           LocalTime sendTime, boolean businessDaysOnly, boolean dailyValidation, RuleStatus status,
                           boolean paused, Instant updatedAt) {

        static RuleView of(Rule rule) {
            SchedulingRule s = rule.scheduling();
            return new RuleView(rule.getId(), rule.getBusinessId(), rule.getRecipientEmail(), rule.getFirstName(),
                    rule.getLastName(), rule.getCaseReference(), rule.getSubject(), s.mode(), s.sendDate(),
                    s.dueDate(), s.daysBefore(), s.startDate(), s.endDate(), rule.getSendTime(), s.businessDaysOnly(),
                    rule.isDailyValidation(), rule.getStatus(), rule.isPaused(), rule.getUpdatedAt());
        }
    }

    private final RuleRepository rules;
    private final CurrentUser currentUser;

    public RuleController(RuleRepository rules, CurrentUser currentUser) {
        this.rules = rules;
        this.currentUser = currentUser;
    }

    @GetMapping
    @Transactional(readOnly = true)
    public List<RuleView> list(Authentication authentication) {
        return rules.findAllByOwnerIdOrderByBusinessIdAsc(currentUser.id(authentication)).stream()
                .map(RuleView::of).toList();
    }
}
