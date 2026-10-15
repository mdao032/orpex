package com.orpex.api;

import static com.orpex.CsrfSupport.realCsrf;
import static com.orpex.MockMvcSupport.login;
import static com.orpex.MockMvcSupport.send;
import static com.orpex.XlsxFiles.ALL_COLUMNS;
import static com.orpex.XlsxFiles.periodRow;
import static com.orpex.XlsxFiles.xlsx;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.orpex.TestcontainersConfiguration;
import com.orpex.account.Role;
import com.orpex.account.UserAccount;
import com.orpex.account.UserAccountRepository;
import com.orpex.rule.Occurrence;
import com.orpex.rule.OccurrenceRepository;
import com.orpex.rule.OccurrenceState;
import com.orpex.rule.Rule;
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

/** Modification d'une relance à l'écran (spécification §7 et §9). */
@SpringBootTest(properties = {
        "orpex.bootstrap-admin.email= Admin@Test.Local ",
        "orpex.bootstrap-admin.password=secret-de-test"
})
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class RuleEditIT {

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

    /** Configuration d'une semaine (lundi 22 au vendredi 26 novembre 2027) au format de la requête. */
    private static String edit(String time, String end, boolean validation, String body, Boolean confirm) {
        return """
                {"email": "dest@exemple.ml", "firstName": "Awa", "lastName": null, "caseReference": null,
                 "subject": "Relance", "body": "%s", "sendMode": "PERIODE", "sendDate": null, "dueDate": null,
                 "daysBefore": null, "startDate": "2027-11-22", "endDate": "%s", "sendTime": "%s",
                 "businessDaysOnly": true, "dailyValidation": %s, "confirmValidationDrop": %s}
                """.formatted(body, end, time, validation, confirm);
    }

    @Test
    void aChangedTimeAndEndDateRescheduleTheFutureSendsAndAreRemembered() throws Exception {
        MockHttpSession session = session("edit-heure@test.local");
        long id = importAndActivate(session, "E-1", false, List.of());

        send(mvc, put("/api/rules/" + id).session(session), edit("10:30", "2027-11-25", false, "Bonjour", null))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.changes[?(@.field == 'heure_envoi')].after").value("10:30"))
                .andExpect(jsonPath("$.sendsMoved").value(4))
                .andExpect(jsonPath("$.sendsCancelled").value(1));

        List<Occurrence> sends = occurrences.findAllByRuleIdOrderByScheduledDateAsc(id);
        assertThat(sends.getFirst().getScheduledAt()).isEqualTo(Instant.parse("2027-11-22T10:30:00Z"));
        assertThat(sends.getLast().getState()).isEqualTo(OccurrenceState.CANCELLED);
        Rule rule = rules.findByIdAndOwnerId(id, owner("edit-heure@test.local")).orElseThrow();
        assertThat(rule.getEditedInApp()).as("valeurs du fichier au moment de la modification")
                .containsEntry("heure_envoi", "09:00").containsEntry("date_fin", "26/11/2027");
    }

    @Test
    void droppingDailyValidationRequiresAConfirmation() throws Exception {
        MockHttpSession session = session("edit-validation@test.local");
        long id = importAndActivate(session, "E-2", true, List.of());

        send(mvc, put("/api/rules/" + id).session(session), edit("09:00", "2027-11-26", false, "Bonjour", null))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail", containsString("de oui à non")));
        assertThat(states(id)).containsOnly(OccurrenceState.AWAITING_VALIDATION);

        send(mvc, put("/api/rules/" + id).session(session), edit("09:00", "2027-11-26", false, "Bonjour", true))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.changes[0].field").value("validation_quotidienne"));
        assertThat(states(id)).containsOnly(OccurrenceState.PLANNED);
    }

    @Test
    void theSameRulesAsTheImportApply() throws Exception {
        MockHttpSession session = session("edit-regles@test.local");
        long id = importAndActivate(session, "E-3", false, List.of("montant"));

        send(mvc, put("/api/rules/" + id).session(session), edit("09:00", "2027-11-20", false, "Bonjour", null))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail", containsString("date_fin")));
        send(mvc, put("/api/rules/" + id).session(session),
                edit("09:00", "2027-11-26", false, "Reste {{inconnue}}", null))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail", containsString("{{inconnue}}")));
        send(mvc, put("/api/rules/" + id).session(session),
                edit("09:00", "2027-11-26", false, "Reste {{montant}} FCFA", null))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.changes[0].field").value("contenu"));
    }

    @Test
    void aClosedCaseCannotBeEdited() throws Exception {
        MockHttpSession session = session("edit-clos@test.local");
        long id = importAndActivate(session, "E-4", false, List.of());
        send(mvc, post("/api/rules/" + id + "/close").session(session), "").andExpect(status().isOk());

        send(mvc, put("/api/rules/" + id).session(session), edit("10:00", "2027-11-26", false, "Bonjour", null))
                .andExpect(status().isConflict());
    }

    private long importAndActivate(MockHttpSession session, String businessId, boolean validation,
            List<String> freeColumns) throws Exception {
        Map<String, String> row = periodRow(businessId, "dest@exemple.ml", "2027-11-22", "2027-11-26");
        row.put("prenom", "Awa");
        row.put("objet", "Relance");
        row.put("contenu", freeColumns.isEmpty() ? "Bonjour" : "Reste {{montant}}");
        row.put("validation_quotidienne", validation ? "oui" : "non");
        List<String> headers = new ArrayList<>(ALL_COLUMNS);
        freeColumns.forEach(column -> {
            headers.add(column);
            row.put(column, "125 000");
        });
        apply(session, xlsx(headers, row)).andExpect(status().isCreated());
        long ownerId = owner((String) session.getAttribute("test-email"));
        long ruleId = rules.findByOwnerIdAndBusinessId(ownerId, businessId).orElseThrow().getId();
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

    private long owner(String email) {
        return accounts.findByEmail(email)
                .orElseGet(() -> accounts.save(new UserAccount(email, passwordEncoder.encode(PASSWORD), Role.USER)))
                .getId();
    }

    private ResultActions apply(MockHttpSession session, byte[] content) throws Exception {
        return mvc.perform(multipart("/api/imports").file(new MockMultipartFile("file", "relances.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", content))
                .session(session).with(realCsrf(mvc)));
    }
}
