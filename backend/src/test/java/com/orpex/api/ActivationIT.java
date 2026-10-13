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
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
        "orpex.bootstrap-admin.email= Admin@Test.Local ",
        "orpex.bootstrap-admin.password=secret-de-test"
})
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class ActivationIT {

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
    Clock clock;

    @Test
    void activationCreatesOneSendPerDayAtTheSendTimeOfBamako() throws Exception {
        long owner = user("active@test.local");
        MockHttpSession session = login(mvc, "active@test.local", PASSWORD);
        long id = rule(owner, "ACT-1", WEEK, false, RuleStatus.ACTIVE);
        mvc.perform(get("/api/rules").session(session))
                .andExpect(jsonPath("$[0].forecast.planned").value(5))
                .andExpect(jsonPath("$[0].forecast.first").value("2027-06-07"))
                .andExpect(jsonPath("$[0].forecast.last").value("2027-06-11"));

        send(mvc, post("/api/rules/" + id + "/activate").session(session), "")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.planned").value(5))
                .andExpect(jsonPath("$.missed").value(0))
                .andExpect(jsonPath("$.first").value("2027-06-07"))
                .andExpect(jsonPath("$.last").value("2027-06-11"));

        var sends = occurrences.findAllByRuleIdOrderByScheduledDateAsc(id);
        assertThat(sends).hasSize(5).allMatch(o -> o.getState() == OccurrenceState.PLANNED);
        assertThat(sends.getFirst().getScheduledAt()).as("09:00 à Bamako = 09:00 UTC")
                .isEqualTo(Instant.parse("2027-06-07T09:00:00Z"));
        assertThat(sends.getFirst().getCreatedBy()).isEqualTo(owner);
        assertThat(rules.findByIdAndOwnerId(id, owner).orElseThrow().isActivated()).isTrue();
        mvc.perform(get("/api/rules").session(session))
                .andExpect(jsonPath("$[0].forecast").doesNotExist());
    }

    @Test
    void withDailyValidationSendsWaitForTheValidation() throws Exception {
        long owner = user("valideur@test.local");
        long id = rule(owner, "ACT-2", WEEK, true, RuleStatus.ACTIVE);

        send(mvc, post("/api/rules/" + id + "/activate").session(login(mvc, "valideur@test.local", PASSWORD)), "")
                .andExpect(status().isOk());

        assertThat(occurrences.findAllByRuleIdOrderByScheduledDateAsc(id))
                .extracting(Occurrence::getState).containsOnly(OccurrenceState.AWAITING_VALIDATION);
    }

    @Test
    void todaysSendWhoseTimeHasPassedIsMissedAndNeverCaughtUp() throws Exception {
        long owner = user("tardif@test.local");
        LocalDate today = LocalDate.now(clock);
        long id = rule(owner, "ACT-3", SchedulingRule.once(today, false), false, RuleStatus.ACTIVE, LocalTime.MIDNIGHT);

        send(mvc, post("/api/rules/" + id + "/activate").session(login(mvc, "tardif@test.local", PASSWORD)), "")
                .andExpect(jsonPath("$.planned").value(0))
                .andExpect(jsonPath("$.missed").value(1));

        assertThat(occurrences.findAllByRuleIdOrderByScheduledDateAsc(id))
                .singleElement().extracting(Occurrence::getState).isEqualTo(OccurrenceState.MISSED);
    }

    @Test
    void aRuleIsActivatedOnceOnlyAndOnlyByItsOwner() throws Exception {
        long owner = user("unique@test.local");
        user("intrus@test.local");
        MockHttpSession session = login(mvc, "unique@test.local", PASSWORD);
        long id = rule(owner, "ACT-4", WEEK, false, RuleStatus.ACTIVE);

        send(mvc, post("/api/rules/" + id + "/activate").session(login(mvc, "intrus@test.local", PASSWORD)), "")
                .andExpect(status().isNotFound());
        send(mvc, post("/api/rules/" + id + "/activate").session(session), "").andExpect(status().isOk());
        send(mvc, post("/api/rules/" + id + "/activate").session(session), "")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("La relance ACT-4 est déjà activée."));

        assertThat(occurrences.countByRuleId(id)).isEqualTo(5);
    }

    @Test
    void activateAllSkipsClosedAndAlreadyActivatedRules() throws Exception {
        long owner = user("lot@test.local");
        MockHttpSession session = login(mvc, "lot@test.local", PASSWORD);
        long first = rule(owner, "LOT-1", WEEK, false, RuleStatus.ACTIVE);
        long closed = rule(owner, "LOT-2", WEEK, false, RuleStatus.CLOSED);
        rule(owner, "LOT-3", WEEK, false, RuleStatus.ACTIVE);
        send(mvc, post("/api/rules/" + first + "/activate").session(session), "");

        send(mvc, post("/api/rules/activate-all").session(session), "")
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].businessId").value("LOT-3"));

        assertThat(occurrences.countByRuleId(closed)).isZero();
        send(mvc, post("/api/rules/" + closed + "/activate").session(session), "")
                .andExpect(status().isConflict());
    }

    private long rule(long owner, String id, SchedulingRule schedule, boolean validation, RuleStatus status) {
        return rule(owner, id, schedule, validation, status, LocalTime.of(9, 0));
    }

    private long rule(long owner, String id, SchedulingRule schedule, boolean validation, RuleStatus status,
            LocalTime time) {
        ImportedRule imported = new ImportedRule(id, "dest@exemple.ml", "Awa", null, null, "Relance", "Bonjour",
                schedule, time, validation, status);
        return rules.save(Rule.fromImport(imported, "a".repeat(64), owner, Instant.now())).getId();
    }

    private long user(String email) {
        return accounts.findByEmail(email)
                .orElseGet(() -> accounts.save(new UserAccount(email, passwordEncoder.encode(PASSWORD), Role.USER)))
                .getId();
    }
}
