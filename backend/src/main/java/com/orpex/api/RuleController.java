package com.orpex.api;

import com.orpex.account.CurrentUser;
import com.orpex.core.calendar.ScheduleCalculator;
import com.orpex.core.calendar.SchedulingRule;
import com.orpex.core.calendar.SendMode;
import com.orpex.core.importing.RuleStatus;
import com.orpex.rule.ActivationService;
import com.orpex.rule.ActivationService.ActivationResult;
import com.orpex.rule.ActivationService.Forecast;
import com.orpex.rule.ClosureService;
import com.orpex.rule.ClosureService.ClosureResult;
import com.orpex.rule.Occurrence;
import com.orpex.rule.OccurrenceRepository;
import com.orpex.rule.OccurrenceState;
import com.orpex.rule.Rule;
import com.orpex.rule.RuleRepository;
import com.orpex.rule.SendingControlService;
import com.orpex.rule.SendingControlService.RulePauseResult;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

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

    /** Un envoi : jour et heure prévus, état, et ce qui s'est passé (date d'envoi réelle, tentatives, erreur). */
    public record OccurrenceView(long id, LocalDate scheduledDate, Instant scheduledAt, OccurrenceState state,
                                 Instant validatedAt, Instant sentAt, int attempts, String lastError) {

        static OccurrenceView of(Occurrence o) {
            return new OccurrenceView(o.getId(), o.getScheduledDate(), o.getScheduledAt(), o.getState(),
                    o.getValidatedAt(), o.getSentAt(), o.getAttempts(), o.getLastError());
        }
    }

    /** Détail d'une relance : sa configuration, son message, ses envois et leur nombre par état. */
    public record RuleDetail(RuleView rule, String body, List<OccurrenceView> sends,
                             Map<OccurrenceState, Long> countsByState) {
    }

    private final RuleRepository rules;
    private final OccurrenceRepository occurrences;
    private final ActivationService activationService;
    private final ClosureService closureService;
    private final SendingControlService sendingControl;
    private final CurrentUser currentUser;

    public RuleController(RuleRepository rules, OccurrenceRepository occurrences, ActivationService activationService,
            ClosureService closureService, SendingControlService sendingControl, CurrentUser currentUser) {
        this.rules = rules;
        this.occurrences = occurrences;
        this.activationService = activationService;
        this.closureService = closureService;
        this.sendingControl = sendingControl;
        this.currentUser = currentUser;
    }

    /** Suspend les envois de la relance, sans les supprimer. */
    @PostMapping("/{id}/pause")
    public RulePauseResult pause(@PathVariable long id, Authentication authentication) {
        return sendingControl.setRulePause(currentUser.id(authentication), id, true);
    }

    /** Reprise : les envois dont l'heure est passée pendant la pause deviennent manqués. */
    @PostMapping("/{id}/resume")
    public RulePauseResult resume(@PathVariable long id, Authentication authentication) {
        return sendingControl.setRulePause(currentUser.id(authentication), id, false);
    }

    /** Dossier réglé : statut clos, envois futurs non partis annulés. */
    @PostMapping("/{id}/close")
    public ClosureResult close(@PathVariable long id, Authentication authentication) {
        return closureService.close(currentUser.id(authentication), id);
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

    @GetMapping("/{id}")
    @Transactional(readOnly = true)
    public RuleDetail detail(@PathVariable long id, Authentication authentication) {
        Rule rule = rules.findByIdAndOwnerId(id, currentUser.id(authentication))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Relance introuvable."));
        List<OccurrenceView> sends = occurrences.findAllByRuleIdOrderByScheduledDateAsc(rule.getId()).stream()
                .map(OccurrenceView::of).toList();
        Map<OccurrenceState, Long> counts = new EnumMap<>(OccurrenceState.class);
        sends.forEach(send -> counts.merge(send.state(), 1L, Long::sum));
        Forecast forecast = rule.isActivated() || rule.getStatus() != RuleStatus.ACTIVE
                ? null : activationService.forecast(rule, activationService.calculator());
        return new RuleDetail(RuleView.of(rule, forecast), rule.getBody(), sends, counts);
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
