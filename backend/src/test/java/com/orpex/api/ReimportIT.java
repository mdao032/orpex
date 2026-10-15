package com.orpex.api;

import static com.orpex.CsrfSupport.realCsrf;
import static com.orpex.MockMvcSupport.login;
import static com.orpex.MockMvcSupport.send;
import static com.orpex.XlsxFiles.ALL_COLUMNS;
import static com.orpex.XlsxFiles.periodRow;
import static com.orpex.XlsxFiles.xlsx;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.orpex.TestcontainersConfiguration;
import com.orpex.account.Role;
import com.orpex.account.UserAccount;
import com.orpex.account.UserAccountRepository;
import com.orpex.core.importing.RuleStatus;
import com.orpex.rule.Occurrence;
import com.orpex.rule.OccurrenceRepository;
import com.orpex.rule.OccurrenceState;
import com.orpex.rule.RuleRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** Ré-import (spécification §9) : mise à jour par id, aperçu détaillé, envois futurs recalculés, rien de supprimé. */
@SpringBootTest(properties = {
        "orpex.bootstrap-admin.email= Admin@Test.Local ",
        "orpex.bootstrap-admin.password=secret-de-test"
})
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class ReimportIT {

    private static final String PASSWORD = "mot-de-passe-solide";

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

    /** Du lundi 22 au vendredi 26 novembre 2027, à 09:00. */
    private static Map<String, String> week(String id) {
        return periodRow(id, "dest@exemple.ml", "2027-11-22", "2027-11-26");
    }

    @Test
    void aChangedTimeAndEndDateMoveTheFutureSendsAndCancelTheLastDay() throws Exception {
        MockHttpSession session = session("reimport-heure@test.local");
        long owner = owner("reimport-heure@test.local");
        long id = importAndActivate(session, week("R-1"));
        Map<String, String> v2 = week("R-1");
        v2.put("date_fin", "2027-11-25");
        v2.put("heure_envoi", "10:30");

        preview(session, xlsx(ALL_COLUMNS, v2))
                .andExpect(jsonPath("$.rows[0].action").value("UPDATE"))
                .andExpect(jsonPath("$.rows[0].changes[?(@.field == 'heure_envoi')].before").value("09:00"))
                .andExpect(jsonPath("$.rows[0].changes[?(@.field == 'heure_envoi')].after").value("10:30"))
                .andExpect(jsonPath("$.rows[0].changes[?(@.field == 'date_fin')].after").value("25/11/2027"))
                .andExpect(jsonPath("$.rows[0].sendImpact.moved").value(4))
                .andExpect(jsonPath("$.rows[0].sendImpact.cancelled").value(1))
                .andExpect(jsonPath("$.counts.toUpdate").value(1));

        apply(session, xlsx(ALL_COLUMNS, v2), false)
                .andExpect(jsonPath("$.updated").value(1))
                .andExpect(jsonPath("$.sendsMoved").value(4))
                .andExpect(jsonPath("$.sendsCancelled").value(1));

        List<Occurrence> sends = occurrences.findAllByRuleIdOrderByScheduledDateAsc(id);
        assertThat(sends).extracting(Occurrence::getState).containsExactly(OccurrenceState.PLANNED,
                OccurrenceState.PLANNED, OccurrenceState.PLANNED, OccurrenceState.PLANNED, OccurrenceState.CANCELLED);
        assertThat(sends.getFirst().getScheduledAt()).as("10:30 à Bamako").isEqualTo(Instant.parse("2027-11-22T10:30:00Z"));
        assertThat(rules.findByIdAndOwnerId(id, owner).orElseThrow().getSendTime()).hasToString("10:30");
    }

    @Test
    void droppingDailyValidationIsAnnouncedAndTheWaitingSendsBecomePlanned() throws Exception {
        MockHttpSession session = session("reimport-validation@test.local");
        Map<String, String> v1 = week("R-2");
        v1.put("validation_quotidienne", "oui");
        long id = importAndActivate(session, v1);
        assertThat(states(id)).containsOnly(OccurrenceState.AWAITING_VALIDATION);

        preview(session, xlsx(ALL_COLUMNS, week("R-2")))
                .andExpect(jsonPath("$.rows[0].warnings",
                        hasItem("validation_quotidienne : passe de oui à non, les envois partiront sans validation")))
                .andExpect(jsonPath("$.rows[0].sendImpact.moved").value(5));
        apply(session, xlsx(ALL_COLUMNS, week("R-2")), false);

        assertThat(states(id)).containsOnly(OccurrenceState.PLANNED);
    }

    @Test
    void statusClosedInTheFileClosesTheCase() throws Exception {
        MockHttpSession session = session("reimport-clos@test.local");
        long owner = owner("reimport-clos@test.local");
        long id = importAndActivate(session, week("R-3"));
        Map<String, String> closed = week("R-3");
        closed.put("statut", "clos");

        preview(session, xlsx(ALL_COLUMNS, closed))
                .andExpect(jsonPath("$.rows[0].closing").value(true))
                .andExpect(jsonPath("$.rows[0].sendImpact.cancelled").value(5));
        apply(session, xlsx(ALL_COLUMNS, closed), false);

        assertThat(rules.findByIdAndOwnerId(id, owner).orElseThrow().getStatus()).isEqualTo(RuleStatus.CLOSED);
        assertThat(states(id)).containsOnly(OccurrenceState.CANCELLED);
    }

    @Test
    void rulesAbsentFromTheFileAreListedAndClosedOnlyOnRequest() throws Exception {
        MockHttpSession session = session("reimport-absent@test.local");
        long owner = owner("reimport-absent@test.local");
        importAndActivate(session, week("R-4"));
        long absent = importAndActivate(session, week("R-5"));

        preview(session, xlsx(ALL_COLUMNS, week("R-4")))
                .andExpect(jsonPath("$.absent[0].businessId").value("R-5"));
        apply(session, xlsx(ALL_COLUMNS, week("R-4")), false).andExpect(jsonPath("$.closedAbsent").value(0));
        assertThat(rules.findByIdAndOwnerId(absent, owner).orElseThrow().getStatus()).isEqualTo(RuleStatus.ACTIVE);

        apply(session, xlsx(ALL_COLUMNS, week("R-4")), true)
                .andExpect(jsonPath("$.closedAbsent").value(1))
                .andExpect(jsonPath("$.sendsCancelled").value(5));
        assertThat(rules.findByIdAndOwnerId(absent, owner).orElseThrow().getStatus()).isEqualTo(RuleStatus.CLOSED);
    }

    @Test
    void aChangedFreeColumnUsedAsVariableIsDetected() throws Exception {
        MockHttpSession session = session("reimport-variable@test.local");
        List<String> headers = new ArrayList<>(ALL_COLUMNS);
        headers.add("montant");
        Map<String, String> v1 = week("R-6");
        v1.put("contenu", "Il reste {{montant}} FCFA.");
        v1.put("montant", "125 000");
        apply(session, xlsx(headers, v1), false).andExpect(jsonPath("$.created").value(1));
        Map<String, String> v2 = new java.util.HashMap<>(v1);
        v2.put("montant", "80 000");

        preview(session, xlsx(headers, v2))
                .andExpect(jsonPath("$.rows[0].action").value("UPDATE"))
                .andExpect(jsonPath("$.rows[0].changes[0].field").value("montant"))
                .andExpect(jsonPath("$.rows[0].changes[0].after").value("80 000"));
    }

    private long importAndActivate(MockHttpSession session, Map<String, String> row) throws Exception {
        apply(session, xlsx(ALL_COLUMNS, row), false).andExpect(status().isCreated());
        long ruleId = rules.findByOwnerIdAndBusinessId(owner(sessionEmail(session)), row.get("id")).orElseThrow().getId();
        send(mvc, post("/api/rules/" + ruleId + "/activate").session(session), "").andExpect(status().isOk());
        return ruleId;
    }

    private List<OccurrenceState> states(long ruleId) {
        return occurrences.findAllByRuleIdOrderByScheduledDateAsc(ruleId).stream().map(Occurrence::getState).toList();
    }

    private MockHttpSession session(String email) throws Exception {
        owner(email);
        MockHttpSession session = login(mvc, email, PASSWORD);
        session.setAttribute("test-email", email);
        return session;
    }

    private static String sessionEmail(MockHttpSession session) {
        return (String) session.getAttribute("test-email");
    }

    private long owner(String email) {
        return accounts.findByEmail(email)
                .orElseGet(() -> accounts.save(new UserAccount(email, passwordEncoder.encode(PASSWORD), Role.USER)))
                .getId();
    }

    private ResultActions preview(MockHttpSession session, byte[] content) throws Exception {
        return mvc.perform(multipart("/api/imports/preview").file(file(content)).session(session).with(realCsrf(mvc)));
    }

    private ResultActions apply(MockHttpSession session, byte[] content, boolean closeAbsent) throws Exception {
        return mvc.perform(multipart("/api/imports").file(file(content)).param("closeAbsent", String.valueOf(closeAbsent))
                .session(session).with(realCsrf(mvc)));
    }

    private static MockMultipartFile file(byte[] content) {
        return new MockMultipartFile("file", "relances.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", content);
    }
}
