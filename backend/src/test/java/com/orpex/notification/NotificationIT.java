package com.orpex.notification;

import static com.orpex.MockMvcSupport.login;
import static com.orpex.MockMvcSupport.send;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.orpex.TestcontainersConfiguration;
import com.orpex.account.Role;
import com.orpex.account.UserAccount;
import com.orpex.account.UserAccountRepository;
import com.orpex.core.calendar.SchedulingRule;
import com.orpex.core.importing.ImportedRule;
import com.orpex.core.importing.RuleStatus;
import com.orpex.core.mail.MailGateway;
import com.orpex.core.mail.MailMessage;
import com.orpex.rule.ActivationService;
import com.orpex.rule.OccurrenceRepository;
import com.orpex.rule.Rule;
import com.orpex.rule.RuleRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatcher;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** Notifications (spécification §11) : résumé quotidien, alerte d'échec, journée de travail réglée par l'admin. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class NotificationIT {

    private static final String PASSWORD = "mot-de-passe-solide";

    @MockitoBean
    MailGateway gateway;

    @Autowired
    MockMvc mvc;

    @Autowired
    DailySummaryService summaries;

    @Autowired
    FailureAlertService failureAlerts;

    @Autowired
    ActivationService activation;

    @Autowired
    RuleRepository rules;

    @Autowired
    OccurrenceRepository occurrences;

    @Autowired
    UserAccountRepository accounts;

    @Autowired
    PasswordEncoder passwordEncoder;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void theDailySummaryLeavesOnceAtTheEndOfAWorkingDay() throws Exception {
        long owner = account("resume@test.local", Role.USER);
        long period = activatedRule(owner, "RES-1", SchedulingRule.period(LocalDate.parse("2030-04-01"),
                LocalDate.parse("2030-04-02"), false));
        long once = activatedRule(owner, "RES-2", SchedulingRule.once(LocalDate.parse("2030-04-01"), false));
        jdbc.update("update occurrences set state = 'SENT', sent_at = now() where rule_id = ? and scheduled_date = ?",
                period, LocalDate.parse("2030-04-01"));
        jdbc.update("update occurrences set state = 'FAILED' where rule_id = ?", once);
        reset(gateway);

        // Lundi 1er avril 2030 (Bamako = UTC) : avant 18:00, rien ; un samedi, rien.
        summaries.sendDueSummaries(Instant.parse("2030-04-01T17:59:00Z"));
        summaries.sendDueSummaries(Instant.parse("2030-03-30T19:00:00Z"));
        verify(gateway, never()).send(argThat(mail(to("resume@test.local"))));

        summaries.sendDueSummaries(Instant.parse("2030-04-01T18:05:00Z"));
        verify(gateway).send(argThat(mail(to("resume@test.local").and(m -> m.body().contains("- envoyées : 1")
                && m.body().contains("- en échec ou à vérifier : 1")
                && m.body().contains("- prévues demain (02/04/2030) : 1")
                && m.body().contains("- RES-2 → dest@exemple.ml, prévu à 09:00 : échec")))));

        reset(gateway);
        summaries.sendDueSummaries(Instant.parse("2030-04-01T19:00:00Z"));
        verify(gateway, never()).send(argThat(mail(to("resume@test.local"))));
    }

    @Test
    void aFailedSendIsReportedOnceToItsOwnerAndToTheAdmins() throws Exception {
        long owner = account("alerte@test.local", Role.USER);
        account("alerte-admin@test.local", Role.ADMIN);
        long rule = activatedRule(owner, "ALE-1", SchedulingRule.once(LocalDate.parse("2030-05-06"), false));
        jdbc.update("update occurrences set state = 'FAILED' where rule_id = ?", rule);
        reset(gateway);

        failureAlerts.sendDueAlerts();
        verify(gateway).send(argThat(mail(to("alerte@test.local").and(m -> m.body().contains("ALE-1 → dest@exemple.ml")))));
        verify(gateway).send(argThat(mail(to("alerte-admin@test.local")
                .and(m -> m.body().contains("ALE-1") && m.body().contains("propriétaire : alerte@test.local")))));

        reset(gateway);
        failureAlerts.sendDueAlerts();
        verify(gateway, never()).send(argThat(mail(to("alerte@test.local"))));
    }

    @Test
    void theAdminSetsTheWorkday() throws Exception {
        account("journee-admin@test.local", Role.ADMIN);
        MockHttpSession admin = login(mvc, "journee-admin@test.local", PASSWORD);

        send(mvc, put("/api/admin/workday").session(admin), "{\"start\": \"18:00\", \"end\": \"08:00\"}")
                .andExpect(status().isBadRequest());
        send(mvc, put("/api/admin/workday").session(admin), "{\"start\": \"07:30\", \"end\": \"17:30\"}")
                .andExpect(status().isOk());
        mvc.perform(get("/api/admin/workday").session(admin))
                .andExpect(jsonPath("$.start").value("07:30:00"))
                .andExpect(jsonPath("$.end").value("17:30:00"));
        send(mvc, put("/api/admin/workday").session(admin), "{\"start\": \"08:00\", \"end\": \"18:00\"}")
                .andExpect(status().isOk());
    }

    private static ArgumentMatcher<MailMessage> mail(Predicate<MailMessage> condition) {
        return condition::test;
    }

    private static Predicate<MailMessage> to(String email) {
        return m -> m.to().equals(email);
    }

    private long activatedRule(long owner, String businessId, SchedulingRule scheduling) {
        ImportedRule imported = new ImportedRule(businessId, "dest@exemple.ml", null, null, null, "Relance",
                "Bonjour", scheduling, LocalTime.of(9, 0), false, RuleStatus.ACTIVE);
        long ruleId = rules.save(Rule.fromImport(imported, "a".repeat(64), owner, Instant.now())).getId();
        activation.activate(owner, ruleId);
        assertThat(occurrences.findAllByRuleIdOrderByScheduledDateAsc(ruleId)).isNotEmpty();
        return ruleId;
    }

    private long account(String email, Role role) {
        return accounts.findByEmail(email)
                .orElseGet(() -> accounts.save(new UserAccount(email, passwordEncoder.encode(PASSWORD), role)))
                .getId();
    }
}
