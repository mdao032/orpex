package com.orpex.api;

import static com.orpex.MockMvcSupport.login;
import static com.orpex.MockMvcSupport.send;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
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
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

/** Q10 : un jour férié ajouté ou retiré après l'activation recalcule les envois futurs non partis. */
@SpringBootTest(properties = {
        "orpex.bootstrap-admin.email= Admin@Test.Local ",
        "orpex.bootstrap-admin.password=secret-de-test"
})
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class RescheduleIT {

    private static final String PASSWORD = "mot-de-passe-solide";
    /** Semaine du lundi 6 au vendredi 10 septembre 2027, réservée à ce test. */
    private static final SchedulingRule WEEK = SchedulingRule.period(LocalDate.parse("2027-09-06"),
            LocalDate.parse("2027-09-10"), true);

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

    private long owner;
    private MockHttpSession user;
    private MockHttpSession admin;

    @BeforeEach
    void sessions() throws Exception {
        owner = account("recalcul@test.local", Role.USER);
        account("recalcul-admin@test.local", Role.ADMIN);
        user = login(mvc, "recalcul@test.local", PASSWORD);
        admin = login(mvc, "recalcul-admin@test.local", PASSWORD);
    }

    @Test
    void addingThenRemovingAHolidayCancelsThenRestoresTheSendOfThatDay() throws Exception {
        long id = activated("REC-1", WEEK, true);
        long everyDay = activated("REC-2", WEEK, false);

        send(mvc, post("/api/admin/holidays").session(admin), "{\"day\":\"2027-09-08\",\"label\":\"Fête test\"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.label").value("Fête test"))
                .andExpect(jsonPath("$.rescheduled.cancelled", greaterThanOrEqualTo(1)));
        assertThat(states(id)).containsEntry(LocalDate.parse("2027-09-08"), OccurrenceState.CANCELLED);
        assertThat(states(everyDay)).as("« tous les jours » : pas concernée par les jours fériés")
                .containsEntry(LocalDate.parse("2027-09-08"), OccurrenceState.PLANNED);

        send(mvc, delete("/api/admin/holidays/2027-09-08").session(admin), "")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.added", greaterThanOrEqualTo(1)));
        assertThat(states(id)).as("le jour redevient un jour d'envoi : l'envoi annulé reprend")
                .containsEntry(LocalDate.parse("2027-09-08"), OccurrenceState.PLANNED)
                .hasSize(5);
    }

    @Test
    void aDueDateBecomingAHolidayMovesTheLastSendToThePreviousBusinessDay() throws Exception {
        long id = activated("REC-3", SchedulingRule.beforeDue(LocalDate.parse("2027-09-24"), 0, true), true);
        assertThat(states(id)).containsOnlyKeys(LocalDate.parse("2027-09-24"));

        send(mvc, post("/api/admin/holidays").session(admin), "{\"day\":\"2027-09-24\",\"label\":\"Échéance fériée\"}")
                .andExpect(status().isCreated());

        assertThat(states(id))
                .containsEntry(LocalDate.parse("2027-09-24"), OccurrenceState.CANCELLED)
                .containsEntry(LocalDate.parse("2027-09-23"), OccurrenceState.PLANNED);
        send(mvc, delete("/api/admin/holidays/2027-09-24").session(admin), "");
    }

    @Test
    void sendsThatAlreadyLeftAreNeverTouched() throws Exception {
        long id = activated("REC-4", SchedulingRule.period(LocalDate.parse("2027-09-13"),
                LocalDate.parse("2027-09-17"), true), true);
        jdbc.update("update occurrences set state = 'SENT' where rule_id = ? and scheduled_date = '2027-09-15'", id);

        send(mvc, post("/api/admin/holidays").session(admin), "{\"day\":\"2027-09-15\",\"label\":\"Trop tard\"}")
                .andExpect(status().isCreated());

        assertThat(states(id)).containsEntry(LocalDate.parse("2027-09-15"), OccurrenceState.SENT);
        send(mvc, delete("/api/admin/holidays/2027-09-15").session(admin), "");
    }

    private Map<LocalDate, OccurrenceState> states(long ruleId) {
        Map<LocalDate, OccurrenceState> states = new LinkedHashMap<>();
        for (Occurrence o : occurrences.findAllByRuleIdOrderByScheduledDateAsc(ruleId)) {
            states.put(o.getScheduledDate(), o.getState());
        }
        return states;
    }

    private long activated(String businessId, SchedulingRule schedule, boolean businessDaysOnly) throws Exception {
        SchedulingRule effective = new SchedulingRule(schedule.mode(), schedule.sendDate(), schedule.dueDate(),
                schedule.daysBefore(), schedule.startDate(), schedule.endDate(), businessDaysOnly);
        ImportedRule imported = new ImportedRule(businessId, "dest@exemple.ml", null, null, null, "Relance", "Bonjour",
                effective, LocalTime.of(9, 0), false, RuleStatus.ACTIVE);
        long id = rules.save(Rule.fromImport(imported, "a".repeat(64), owner, Instant.now())).getId();
        send(mvc, post("/api/rules/" + id + "/activate").session(user), "").andExpect(status().isOk());
        return id;
    }

    private long account(String email, Role role) {
        return accounts.findByEmail(email)
                .orElseGet(() -> accounts.save(new UserAccount(email, passwordEncoder.encode(PASSWORD), role)))
                .getId();
    }
}
