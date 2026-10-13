package com.orpex.api;

import com.orpex.account.CurrentUser;
import com.orpex.core.calendar.ScheduleCalculator;
import com.orpex.core.calendar.SchedulingRule;
import com.orpex.core.calendar.SendMode;
import com.orpex.core.importing.RuleStatus;
import com.orpex.rule.ActivationService;
import com.orpex.rule.ActivationService.ActivationResult;
import com.orpex.rule.ActivationService.Forecast;
import com.orpex.rule.Rule;
import com.orpex.rule.RuleRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
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
                           boolean paused, Instant activatedAt, Instant updatedAt, Forecast forecast) {

        /** {@code forecast} : envois que l'activation créerait, seulement pour une relance non activée et non close. */
        static RuleView of(Rule rule, Forecast forecast) {
            SchedulingRule s = rule.scheduling();
            return new RuleView(rule.getId(), rule.getBusinessId(), rule.getRecipientEmail(), rule.getFirstName(),
                    rule.getLastName(), rule.getCaseReference(), rule.getSubject(), s.mode(), s.sendDate(),
                    s.dueDate(), s.daysBefore(), s.startDate(), s.endDate(), rule.getSendTime(), s.businessDaysOnly(),
                    rule.isDailyValidation(), rule.getStatus(), rule.isPaused(), rule.getActivatedAt(),
                    rule.getUpdatedAt(), forecast);
        }
    }

    private final RuleRepository rules;
    private final ActivationService activationService;
    private final CurrentUser currentUser;

    public RuleController(RuleRepository rules, ActivationService activationService, CurrentUser currentUser) {
        this.rules = rules;
        this.activationService = activationService;
        this.currentUser = currentUser;
    }

    /** Crée les envois de la relance ; avant, aucun envoi n'existe. */
    @PostMapping("/{id}/activate")
    public ActivationResult activate(@PathVariable long id, Authentication authentication) {
        return activationService.activate(currentUser.id(authentication), id);
    }

    /** Active toutes les relances non encore activées et non closes. */
    @PostMapping("/activate-all")
    public List<ActivationResult> activateAll(Authentication authentication) {
        return activationService.activateAll(currentUser.id(authentication));
    }

    @GetMapping
    @Transactional(readOnly = true)
    public List<RuleView> list(Authentication authentication) {
        ScheduleCalculator calculator = activationService.calculator();
        return rules.findAllByOwnerIdOrderByBusinessIdAsc(currentUser.id(authentication)).stream()
                .map(rule -> RuleView.of(rule, rule.isActivated() || rule.getStatus() != RuleStatus.ACTIVE
                        ? null : activationService.forecast(rule, calculator)))
                .toList();
    }
}
