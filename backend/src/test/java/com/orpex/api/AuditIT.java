package com.orpex.api;

import static com.orpex.CsrfSupport.realCsrf;
import static com.orpex.MockMvcSupport.login;
import static com.orpex.MockMvcSupport.send;
import static com.orpex.XlsxFiles.ALL_COLUMNS;
import static com.orpex.XlsxFiles.periodRow;
import static com.orpex.XlsxFiles.xlsx;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.orpex.TestcontainersConfiguration;
import com.orpex.account.Role;
import com.orpex.account.UserAccount;
import com.orpex.account.UserAccountRepository;
import com.orpex.audit.AuditEntry;
import com.orpex.audit.AuditEvent;
import com.orpex.audit.AuditRepository;
import com.orpex.rule.RuleRepository;
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
