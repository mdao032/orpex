package com.orpex.api;

import static com.orpex.MockMvcSupport.login;
import static com.orpex.MockMvcSupport.send;
import static org.assertj.core.api.Assertions.assertThat;
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
import com.orpex.rule.Occurrence;
import com.orpex.rule.OccurrenceRepository;
import com.orpex.rule.OccurrenceState;
import com.orpex.rule.Rule;
import com.orpex.rule.RuleRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
        "orpex.bootstrap-admin.email= Admin@Test.Local ",
        "orpex.bootstrap-admin.password=secret-de-test"
})
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class PauseIT {

    private static final String PASSWORD = "mot-de-passe-solide";
    /** Du lundi 7 au vendredi 11 juin 2027. */
    private static final SchedulingRule WEEK = SchedulingRule.period(LocalDate.parse("2027-06-07"),
            LocalDate.parse("2027-06-11"), true);

    @Autowired
    MockMvc mvc;

    @Autowired
    UserAccountRepository accounts;

    @Autowired
    RuleRepository rules;

    @Autowired
    OccurrenceRepository occurrences;

    @Autowired
    PasswordEncoder passwordEncoder;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void sendsWhoseTimePassedDuringARulePauseAreMissedAtResume() throws Exception {
        long owner = user("pause@test.local", Role.USER);
        MockHttpSession session = login(mvc, "pause@test.local", PASSWORD);
        long id = activatedRule(owner, "PAU-1", session);

        send(mvc, post("/api/rules/" + id + "/pause").session(session), "")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paused").value(true));
        mvc.perform(get("/api/rules").session(session)).andExpect(jsonPath("$[0].paused").value(true));
        passTheTimeOfTheFirstTwoSends(id);

        send(mvc, post("/api/rules/" + id + "/resume").session(session), "")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paused").value(false))
                .andExpect(jsonPath("$.missed").value(2));

        assertThat(occurrences.findAllByRuleIdOrderByScheduledDateAsc(id)).extracting(Occurrence::getState)
                .containsExactly(OccurrenceState.MISSED, OccurrenceState.MISSED, OccurrenceState.PLANNED,
                        OccurrenceState.PLANNED, OccurrenceState.PLANNED);
    }

    @Test
    void onlyAnActivatedOpenRuleCanBePausedAndOnlyOnce() throws Exception {
        long owner = user("pause-regles@test.local", Role.USER);
        MockHttpSession session = login(mvc, "pause-regles@test.local", PASSWORD);
        long inactive = rule(owner, "PAU-2");
        long active = activatedRule(owner, "PAU-3", session);

        send(mvc, post("/api/rules/" + inactive + "/pause").session(session), "")
                .andExpect(status().isConflict());
        send(mvc, post("/api/rules/" + active + "/resume").session(session), "")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("La relance PAU-3 n'est pas en pause."));
        send(mvc, post("/api/rules/" + active + "/pause").session(session), "").andExpect(status().isOk());
        send(mvc, post("/api/rules/" + active + "/pause").session(session), "")
                .andExpect(status().isConflict());
    }

    @Test
    void onlyAnAdministratorPausesAllSendingAndEveryoneSeesIt() throws Exception {
        long owner = user("pause-user@test.local", Role.USER);
        user("pause-admin@test.local", Role.ADMIN);
        MockHttpSession userSession = login(mvc, "pause-user@test.local", PASSWORD);
        MockHttpSession admin = login(mvc, "pause-admin@test.local", PASSWORD);
        long id = activatedRule(owner, "PAU-4", userSession);

        send(mvc, post("/api/admin/sending/pause").session(userSession), "").andExpect(status().isForbidden());
        try {
            send(mvc, post("/api/admin/sending/pause").session(admin), "").andExpect(status().isOk());
            mvc.perform(get("/api/sending-status").session(userSession))
                    .andExpect(jsonPath("$.paused").value(true))
                    .andExpect(jsonPath("$.changedAt").exists());
            send(mvc, post("/api/admin/sending/pause").session(admin), "").andExpect(status().isConflict());
            passTheTimeOfTheFirstTwoSends(id);
        } finally {
            send(mvc, post("/api/admin/sending/resume").session(admin), "")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.missed").value(2));
        }

        mvc.perform(get("/api/sending-status").session(userSession)).andExpect(jsonPath("$.paused").value(false));
        assertThat(occurrences.findAllByRuleIdAndStateIn(id, java.util.Set.of(OccurrenceState.MISSED))).hasSize(2);
    }

    /** Simule le temps qui passe : les deux premiers envois de la ligne sont désormais dans le passé. */
    private void passTheTimeOfTheFirstTwoSends(long ruleId) {
        jdbc.update("""
                update occurrences set scheduled_at = now() - interval '1 hour'
                where rule_id = ? and scheduled_date in ('2027-06-07', '2027-06-08')""", ruleId);
    }

    private long activatedRule(long owner, String businessId, MockHttpSession session) throws Exception {
        long id = rule(owner, businessId);
        send(mvc, post("/api/rules/" + id + "/activate").session(session), "").andExpect(status().isOk());
        return id;
    }

    private long rule(long owner, String businessId) {
        ImportedRule imported = new ImportedRule(businessId, "dest@exemple.ml", "Awa", null, null, "Relance",
                "Bonjour", WEEK, LocalTime.of(9, 0), false, RuleStatus.ACTIVE);
        return rules.save(Rule.fromImport(imported, "a".repeat(64), owner, Instant.now())).getId();
    }

    private long user(String email, Role role) {
        return accounts.findByEmail(email)
                .orElseGet(() -> accounts.save(new UserAccount(email, passwordEncoder.encode(PASSWORD), role)))
                .getId();
    }
}
