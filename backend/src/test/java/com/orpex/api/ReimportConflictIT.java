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
import com.orpex.rule.Rule;
import com.orpex.rule.RuleRepository;
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

/** Conflits entre le fichier et l'écran au ré-import (spécification §9) : jamais d'écrasement silencieux. */
@SpringBootTest(properties = {
        "orpex.bootstrap-admin.email= Admin@Test.Local ",
        "orpex.bootstrap-admin.password=secret-de-test"
})
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class ReimportConflictIT {

    private static final String PASSWORD = "mot-de-passe-solide";

    @Autowired
    MockMvc mvc;

    @Autowired
    UserAccountRepository accounts;

    @Autowired
    RuleRepository rules;

    @Autowired
    PasswordEncoder passwordEncoder;

    /** Du lundi 22 au vendredi 26 novembre 2027 à 09:00 ; l'heure passe à 10:30 à l'écran. */
    private static Map<String, String> week(String id) {
        return periodRow(id, "dest@exemple.ml", "2027-11-22", "2027-11-26");
    }

    @Test
    void theSameFileDoesNotUndoAnEditMadeInTheApp() throws Exception {
        MockHttpSession session = session("conflit-meme@test.local");
        long id = importAndEditTime(session, "C-1");

        preview(session, xlsx(ALL_COLUMNS, week("C-1"))).andExpect(jsonPath("$.rows[0].action").value("UNCHANGED"));
        apply(session, xlsx(ALL_COLUMNS, week("C-1")), null).andExpect(jsonPath("$.unchanged").value(1));
        assertThat(rule(id, "conflit-meme@test.local").getSendTime()).hasToString("10:30");
    }

    @Test
    void aConflictMustBeResolvedAndTheAppValueCanBeKept() throws Exception {
        MockHttpSession session = session("conflit-app@test.local");
        long id = importAndEditTime(session, "C-2");
        Map<String, String> v2 = week("C-2");
        v2.put("heure_envoi", "11:00");
        v2.put("date_fin", "2027-11-25");

        preview(session, xlsx(ALL_COLUMNS, v2))
                .andExpect(jsonPath("$.rows[0].action").value("UPDATE"))
                .andExpect(jsonPath("$.rows[0].conflicts").value("heure_envoi"))
                .andExpect(jsonPath("$.counts.withConflicts").value(1));
        apply(session, xlsx(ALL_COLUMNS, v2), null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail", containsString("C-2 (heure_envoi)")));
        assertThat(rule(id, "conflit-app@test.local").getSendTime()).hasToString("10:30");

        apply(session, xlsx(ALL_COLUMNS, v2), "{\"C-2\": {\"heure_envoi\": \"APP\"}}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.updated").value(1));
        Rule kept = rule(id, "conflit-app@test.local");
        assertThat(kept.getSendTime()).as("valeur de l'application gardée").hasToString("10:30");
        assertThat(kept.scheduling().endDate()).as("autre champ pris du fichier").hasToString("2027-11-25");

        preview(session, xlsx(ALL_COLUMNS, v2))
                .andExpect(jsonPath("$.rows[0].action").value("UNCHANGED"));
    }

    @Test
    void theFileValueCanWin() throws Exception {
        MockHttpSession session = session("conflit-fichier@test.local");
        long id = importAndEditTime(session, "C-3");
        Map<String, String> v2 = week("C-3");
        v2.put("heure_envoi", "11:00");

        apply(session, xlsx(ALL_COLUMNS, v2), "{\"C-3\": {\"heure_envoi\": \"FILE\"}}").andExpect(status().isCreated());
        Rule updated = rule(id, "conflit-fichier@test.local");
        assertThat(updated.getSendTime()).hasToString("11:00");
        assertThat(updated.getEditedInApp()).isEmpty();
    }

    private long importAndEditTime(MockHttpSession session, String businessId) throws Exception {
        apply(session, xlsx(ALL_COLUMNS, week(businessId)), null).andExpect(status().isCreated());
        String email = (String) session.getAttribute("test-email");
        long ruleId = rules.findByOwnerIdAndBusinessId(owner(email), businessId).orElseThrow().getId();
        send(mvc, post("/api/rules/" + ruleId + "/activate").session(session), "").andExpect(status().isOk());
        Rule rule = rule(ruleId, email);
        // Même configuration que le fichier, sauf l'heure.
        send(mvc, put("/api/rules/" + ruleId).session(session), """
                {"email": "%s", "firstName": %s, "lastName": %s, "caseReference": %s, "subject": "%s", "body": "%s",
                 "sendMode": "PERIODE", "startDate": "2027-11-22", "endDate": "2027-11-26", "sendTime": "10:30",
                 "businessDaysOnly": %s, "dailyValidation": %s}
                """.formatted(rule.getRecipientEmail(), json(rule.getFirstName()), json(rule.getLastName()),
                json(rule.getCaseReference()), rule.getSubject(), rule.getBody(),
                rule.scheduling().businessDaysOnly(), rule.isDailyValidation()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.changes.length()").value(1))
                .andExpect(jsonPath("$.changes[0].field").value("heure_envoi"));
        return ruleId;
    }

    private static String json(String value) {
        return value == null ? "null" : "\"" + value + "\"";
    }

    private Rule rule(long id, String email) {
        return rules.findByIdAndOwnerId(id, owner(email)).orElseThrow();
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

    private ResultActions preview(MockHttpSession session, byte[] content) throws Exception {
        return mvc.perform(multipart("/api/imports/preview").file(file(content)).session(session).with(realCsrf(mvc)));
    }

    private ResultActions apply(MockHttpSession session, byte[] content, String resolutions) throws Exception {
        var request = multipart("/api/imports").file(file(content)).session(session).with(realCsrf(mvc));
        if (resolutions != null) {
            request.param("resolutions", resolutions);
        }
        return mvc.perform(request);
    }

    private static MockMultipartFile file(byte[] content) {
        return new MockMultipartFile("file", "relances.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", content);
    }
}
