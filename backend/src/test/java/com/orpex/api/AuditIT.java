package com.orpex.api;

import static com.orpex.CsrfSupport.realCsrf;
import static com.orpex.MockMvcSupport.login;
import static com.orpex.MockMvcSupport.send;
import static com.orpex.XlsxFiles.ALL_COLUMNS;
import static com.orpex.XlsxFiles.periodRow;
import static com.orpex.XlsxFiles.xlsx;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.orpex.TestcontainersConfiguration;
import com.orpex.account.Role;
import com.orpex.account.UserAccount;
import com.orpex.account.UserAccountRepository;
import com.orpex.audit.AuditEntry;
import com.orpex.audit.AuditEvent;
import com.orpex.audit.AuditRepository;
import com.orpex.rule.RuleRepository;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

/** Journal d'audit (spécification §11) : chaque action laisse une trace non modifiable, avec son auteur. */
@SpringBootTest(properties = {
        "orpex.bootstrap-admin.email= Admin@Test.Local ",
        "orpex.bootstrap-admin.password=secret-de-test"
})
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class AuditIT {

    private static final String PASSWORD = "mot-de-passe-solide";

    @Autowired
    MockMvc mvc;

    @Autowired
    UserAccountRepository accounts;

    @Autowired
    RuleRepository rules;

    @Autowired
    AuditRepository audit;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PasswordEncoder passwordEncoder;

    @Test
    void importActivationClosureAndEverySendStateAreJournaled() throws Exception {
        long owner = owner("audit-relance@test.local");
        MockHttpSession session = login(mvc, "audit-relance@test.local", PASSWORD);
        mvc.perform(multipart("/api/imports").file(new MockMultipartFile("file", "relances.xlsx",
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                        xlsx(ALL_COLUMNS, periodRow("A-1", "dest@exemple.ml", "2027-11-22", "2027-11-26"))))
                .session(session).with(realCsrf(mvc))).andExpect(status().isCreated());
        long id = rules.findByOwnerIdAndBusinessId(owner, "A-1").orElseThrow().getId();
        send(mvc, post("/api/rules/" + id + "/activate").session(session), "").andExpect(status().isOk());
        send(mvc, post("/api/rules/" + id + "/close").session(session), "").andExpect(status().isOk());

        List<AuditEntry> entries = journal(owner);
        assertThat(entries).extracting(AuditEntry::getEventType).contains(AuditEvent.LOGIN_SUCCEEDED,
                AuditEvent.IMPORT_APPLIED, AuditEvent.RULE_ACTIVATED, AuditEvent.RULE_CLOSED);
        assertThat(entries).filteredOn(e -> e.getEventType() == AuditEvent.RULE_CLOSED)
                .singleElement().satisfies(e -> {
                    assertThat(e.getActorId()).isEqualTo(owner);
                    assertThat(e.getObjectId()).isEqualTo("A-1");
                    assertThat(e.getNewValue()).isEqualTo("5 envoi(s) annulé(s)");
                });
        List<AuditEntry> sends = entries.stream()
                .filter(e -> e.getEventType() == AuditEvent.SEND_STATE_CHANGED).toList();
        assertThat(sends).as("un par envoi annulé, écrit par la base").hasSize(5).allSatisfy(e -> {
            assertThat(e.getOldValue()).isEqualTo("PLANNED");
            assertThat(e.getNewValue()).contains("\"etat\": \"CANCELLED\"", "\"destinataire\": \"dest@exemple.ml\"",
                    "\"relance\": \"A-1\"");
            assertThat(e.getOwnerId()).isEqualTo(owner);
        });
    }

    @Test
    void eachUserReadsTheirOwnJournalAndTheAdminReadsEverything() throws Exception {
        long owner = owner("audit-lecture@test.local");
        owner("audit-autre@test.local");
        MockHttpSession session = login(mvc, "audit-lecture@test.local", PASSWORD);
        MockHttpSession other = login(mvc, "audit-autre@test.local", PASSWORD);
        mvc.perform(multipart("/api/imports").file(new MockMultipartFile("file", "relances.xlsx",
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                        xlsx(ALL_COLUMNS, periodRow("L-1", "lecture@exemple.ml", "2027-11-22", "2027-11-23"))))
                .session(session).with(realCsrf(mvc))).andExpect(status().isCreated());
        long id = rules.findByOwnerIdAndBusinessId(owner, "L-1").orElseThrow().getId();
        send(mvc, post("/api/rules/" + id + "/activate").session(session), "").andExpect(status().isOk());
        send(mvc, post("/api/rules/" + id + "/close").session(session), "").andExpect(status().isOk());

        mvc.perform(get("/api/audit").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].eventLabel").exists())
                .andExpect(jsonPath("$[?(@.event == 'SEND_STATE_CHANGED')].after", hasItem(
                        "L-1, lecture@exemple.ml, prévu le 22/11/2027 09:00 : Annulé")))
                .andExpect(jsonPath("$[?(@.event == 'SEND_STATE_CHANGED')].before", hasItem("Prévu")))
                .andExpect(jsonPath("$[?(@.event == 'RULE_CLOSED')].actor", hasItem("audit-lecture@test.local")));
        mvc.perform(get("/api/audit").session(other))
                .andExpect(jsonPath("$[?(@.objectId == 'L-1')]", empty()));
        mvc.perform(get("/api/admin/audit").session(session)).andExpect(status().isForbidden());

        accounts.findByEmail("audit-admin@test.local").orElseGet(() -> accounts.save(new UserAccount(
                "audit-admin@test.local", passwordEncoder.encode(PASSWORD), Role.ADMIN)));
        MockHttpSession admin = login(mvc, "audit-admin@test.local", PASSWORD);
        mvc.perform(get("/api/admin/audit").session(admin))
                .andExpect(jsonPath("$[?(@.objectId == 'L-1')]", not(empty())));
        String csv = mvc.perform(get("/api/audit/export").session(session))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", containsString("journal-orpex.csv")))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(csv).startsWith("\uFEFFDate;Auteur;Événement;Objet;Identifiant;Avant;Après\r\n")
                .contains(";audit-lecture@test.local;Dossier clos;RULE;L-1;;2 envoi(s) annulé(s)\r\n");
    }

    @Test
    void aPeriodOnlyKeepsItsDays() throws Exception {
        owner("audit-periode@test.local");
        MockHttpSession session = login(mvc, "audit-periode@test.local", PASSWORD);
        mvc.perform(get("/api/audit").param("from", "2000-01-01").param("to", "2000-01-31").session(session))
                .andExpect(jsonPath("$", empty()));
        mvc.perform(get("/api/audit").param("from", "2000-02-01").param("to", "2000-01-31").session(session))
                .andExpect(status().isBadRequest());
    }

    @Test
    void aFailedLoginIsJournaled() throws Exception {
        long owner = owner("audit-echec@test.local");
        mvc.perform(post("/login").param("username", "audit-echec@test.local").param("password", "mauvais")
                .with(realCsrf(mvc))).andExpect(status().isUnauthorized());

        assertThat(journal(owner)).filteredOn(e -> e.getEventType() == AuditEvent.LOGIN_FAILED).singleElement()
                .satisfies(e -> assertThat(e.getNewValue()).isEqualTo("identifiants incorrects"));
    }

    @Test
    void theJournalCannotBeChangedNorDeleted() {
        assertThatThrownBy(() -> jdbc.update("update audit_log set new_value = 'x'"))
                .hasMessageContaining("conservation obligatoire");
        assertThatThrownBy(() -> jdbc.update("delete from audit_log"))
                .hasMessageContaining("conservation obligatoire");
    }

    private List<AuditEntry> journal(long owner) {
        return audit.findByOwnerIdOrActorIdOrderByOccurredAtDescIdDesc(owner, owner, PageRequest.of(0, 100));
    }

    private long owner(String email) {
        return accounts.findByEmail(email)
                .orElseGet(() -> accounts.save(new UserAccount(email, passwordEncoder.encode(PASSWORD), Role.USER)))
                .getId();
    }
}
