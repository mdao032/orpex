package com.orpex.validation;

import static com.orpex.MockMvcSupport.login;
import static com.orpex.MockMvcSupport.send;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
import com.orpex.rule.ActivationService;
import com.orpex.rule.Occurrence;
import com.orpex.rule.OccurrenceRepository;
import com.orpex.rule.OccurrenceState;
import com.orpex.rule.Rule;
import com.orpex.rule.RuleRepository;
import com.orpex.sending.SendWorker;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** Validation quotidienne (spécification §7) : sans validation, rien ne part ; jamais en retard. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class ValidationIT {

    private static final String PASSWORD = "mot-de-passe-solide";

    @MockitoBean
    MailGateway gateway;

    @Autowired
    MockMvc mvc;

    @Autowired
    SendWorker worker;

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

    @Autowired
    Clock clock;

    private long owner;
    private MockHttpSession session;

    @BeforeEach
    void setUp() throws Exception {
        reset(gateway);
        owner = account("valide@test.local");
        account("intrus-validation@test.local");
        session = login(mvc, "valide@test.local", PASSWORD);
    }

    @Test
    void todaysSendsAreValidatedOrUncheckedByTheirOwner() throws Exception {
        long kept = awaitingToday("VAL-1", "2 hours");
        long unchecked = awaitingToday("VAL-2", "3 hours");
        jdbc.update("insert into occurrences (rule_id, scheduled_date, scheduled_at, state, created_by) "
                + "values ((select rule_id from occurrences where id = ?), '2027-01-04', now(), 'SENT', ?)", kept, owner);

        mvc.perform(get("/api/validation/today").session(session))
                .andExpect(jsonPath("$[?(@.sendId == " + kept + ")].businessId").value("VAL-1"))
                .andExpect(jsonPath("$[?(@.sendId == " + kept + ")].open").value(true))
                .andExpect(jsonPath("$[?(@.sendId == " + kept + ")].alreadySent").value(1));

        send(mvc, post("/api/validation").session(session),
                "{\"validate\":[" + kept + "],\"reject\":[" + unchecked + "]}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.validated").value(1))
                .andExpect(jsonPath("$.rejected").value(1))
                .andExpect(jsonPath("$.refused.length()").value(0));

        Occurrence validated = occurrences.findById(kept).orElseThrow();
        assertThat(validated.getState()).isEqualTo(OccurrenceState.VALIDATED);
        assertThat(validated.getValidatedBy()).isEqualTo(owner);
        assertThat(validated.getValidatedAt()).isNotNull();
        assertThat(occurrences.findById(unchecked).orElseThrow().getState()).isEqualTo(OccurrenceState.NOT_VALIDATED);
    }

    @Test
    void validationIsRefusedAfterTheCutoffForAnotherDayOrForAnotherUser() throws Exception {
        long tooLate = awaitingToday("VAL-3", "10 minutes");
        long tomorrow = awaitingToday("VAL-4", "2 hours");
        jdbc.update("update occurrences set scheduled_date = scheduled_date + 1 where id = ?", tomorrow);
        long mine = awaitingToday("VAL-5", "2 hours");

        mvc.perform(get("/api/validation/today").session(session))
                .andExpect(jsonPath("$[?(@.sendId == " + tooLate + ")].open").value(false))
                .andExpect(jsonPath("$[?(@.sendId == " + tomorrow + ")]").isEmpty());

        send(mvc, post("/api/validation").session(session), "{\"validate\":[" + tooLate + "," + tomorrow + "]}")
                .andExpect(jsonPath("$.validated").value(0))
                .andExpect(jsonPath("$.refused[0].reason")
                        .value("L'heure limite de validation est passée : cet envoi ne partira pas."))
                .andExpect(jsonPath("$.refused[1].reason")
                        .value("Seuls les envois d'aujourd'hui se valident, le jour même."));
        send(mvc, post("/api/validation").session(login(mvc, "intrus-validation@test.local", PASSWORD)),
                "{\"validate\":[" + mine + "]}")
                .andExpect(jsonPath("$.refused[0].reason").value("Envoi introuvable."));
        assertThat(occurrences.findById(mine).orElseThrow().getState()).isEqualTo(OccurrenceState.AWAITING_VALIDATION);
    }

    @Test
    void atSendTimeAValidatedSendLeavesAndAnUnvalidatedOneNeverDoes() throws Exception {
        long validated = awaitingToday("VAL-6", "2 hours");
        long forgotten = awaitingToday("VAL-7", "2 hours");
        send(mvc, post("/api/validation").session(session), "{\"validate\":[" + validated + "]}");
        jdbc.update("update occurrences set scheduled_at = now() - interval '1 minute' where id in (?, ?)",
                validated, forgotten);

        worker.processDueSends();

        assertThat(occurrences.findById(validated).orElseThrow().getState()).isEqualTo(OccurrenceState.SENT);
        assertThat(occurrences.findById(forgotten).orElseThrow().getState()).isEqualTo(OccurrenceState.NOT_VALIDATED);
        verify(gateway, times(1)).send(any());
    }

    @Test
    void theOwnerCanChangeTheDecisionOfTheDayUntilTheCutoff() throws Exception {
        long send = awaitingToday("VAL-8", "2 hours");
        send(mvc, post("/api/validation").session(session), "{\"reject\":[" + send + "]}");
        mvc.perform(get("/api/validation/today").session(session))
                .andExpect(jsonPath("$[?(@.sendId == " + send + ")].decision").value("UNCHECKED"));

        send(mvc, post("/api/validation/reopen").session(session), "{\"sendId\":" + send + "}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.decision").value("PENDING"));
        Occurrence reopened = occurrences.findById(send).orElseThrow();
        assertThat(reopened.getState()).isEqualTo(OccurrenceState.AWAITING_VALIDATION);
        assertThat(reopened.getValidatedBy()).isNull();

        send(mvc, post("/api/validation").session(session), "{\"validate\":[" + send + "]}");
        send(mvc, post("/api/validation/reopen").session(session), "{\"sendId\":" + send + "}")
                .andExpect(status().isOk());
        assertThat(occurrences.findById(send).orElseThrow().getState())
                .isEqualTo(OccurrenceState.AWAITING_VALIDATION);
    }

    @Test
    void aDecisionCannotBeChangedAfterTheCutoffNorWhenNobodyDecided() throws Exception {
        long tooLate = awaitingToday("VAL-9", "10 minutes");
        jdbc.update("update occurrences set state = 'VALIDATED', validated_by = ?, validated_at = now() where id = ?",
                owner, tooLate);
        long expired = awaitingToday("VAL-10", "2 hours");
        jdbc.update("update occurrences set state = 'NOT_VALIDATED' where id = ?", expired);
        long mine = awaitingToday("VAL-11", "2 hours");
        send(mvc, post("/api/validation").session(session), "{\"validate\":[" + mine + "]}");

        send(mvc, post("/api/validation/reopen").session(session), "{\"sendId\":" + tooLate + "}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("Trop tard : l'heure limite de validation de cet envoi est passée."));
        send(mvc, post("/api/validation/reopen").session(session), "{\"sendId\":" + expired + "}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("Aucune décision à reprendre sur cet envoi."));
        send(mvc, post("/api/validation/reopen").session(login(mvc, "intrus-validation@test.local", PASSWORD)),
                "{\"sendId\":" + mine + "}")
                .andExpect(status().isNotFound());
        assertThat(occurrences.findById(mine).orElseThrow().getState()).isEqualTo(OccurrenceState.VALIDATED);
    }

    /** Envoi d'aujourd'hui en attente de validation, prévu dans {@code delay} (intervalle PostgreSQL). */
    private long awaitingToday(String businessId, String delay) {
        ImportedRule imported = new ImportedRule(businessId + "-" + System.nanoTime(), "dest@exemple.ml", null, null,
                "FAC-" + businessId, "Relance", "Bonjour", SchedulingRule.once(LocalDate.parse("2027-12-01"), false),
                LocalTime.of(9, 0), true, RuleStatus.ACTIVE);
        long ruleId = rules.save(Rule.fromImport(imported, "a".repeat(64), owner, Instant.now())).getId();
        jdbc.update("update rules set business_id = ? where id = ?", businessId, ruleId);
        activation.activate(owner, ruleId);
        long id = occurrences.findAllByRuleIdOrderByScheduledDateAsc(ruleId).getFirst().getId();
        jdbc.update("update occurrences set scheduled_date = ?, scheduled_at = now() + cast(? as interval) where id = ?",
                LocalDate.now(clock), delay, id);
        return id;
    }

    private long account(String email) {
        return accounts.findByEmail(email)
                .orElseGet(() -> accounts.save(new UserAccount(email, passwordEncoder.encode(PASSWORD), Role.USER)))
                .getId();
    }
}
