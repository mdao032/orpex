package com.orpex.api;

import static com.orpex.CsrfSupport.realCsrf;
import static com.orpex.MockMvcSupport.login;
import static com.orpex.XlsxFiles.ALL_COLUMNS;
import static com.orpex.XlsxFiles.periodRow;
import static com.orpex.XlsxFiles.xlsx;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.orpex.TestcontainersConfiguration;
import com.orpex.account.Role;
import com.orpex.account.UserAccount;
import com.orpex.account.UserAccountRepository;
import com.orpex.rule.RuleRepository;
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

@SpringBootTest(properties = {
        "orpex.bootstrap-admin.email= Admin@Test.Local ",
        "orpex.bootstrap-admin.password=secret-de-test"
})
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class ImportApplyIT {

    private static final String PASSWORD = "mot-de-passe-solide";

    @Autowired
    MockMvc mvc;

    @Autowired
    UserAccountRepository accounts;

    @Autowired
    RuleRepository rules;

    @Autowired
    PasswordEncoder passwordEncoder;

    @Test
    void createsTheValidRowsAndRecordsTheImport() throws Exception {
        long owner = user("applique@test.local");
        MockHttpSession session = login(mvc, "applique@test.local", PASSWORD);
        Map<String, String> invalid = periodRow("A-3", "pas-un-email", "2027-05-03", "2027-05-07");

        apply(session, "mai.xlsx", xlsx(ALL_COLUMNS,
                periodRow("A-1", "a@exemple.ml", "2027-05-03", "2027-05-07"),
                periodRow("A-2", "b@exemple.ml", "2027-05-10", "2027-05-14"),
                invalid))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.created").value(2))
                .andExpect(jsonPath("$.rejected").value(1))
                .andExpect(jsonPath("$.notApplied").value(0));

        assertThat(rules.findAllByOwnerIdOrderByBusinessIdAsc(owner))
                .extracting(r -> r.getBusinessId()).containsExactly("A-1", "A-2");
        mvc.perform(get("/api/rules").session(session))
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].businessId").value("A-1"))
                .andExpect(jsonPath("$[0].sendMode").value("PERIODE"))
                .andExpect(jsonPath("$[0].startDate").value("2027-05-03"));
        mvc.perform(get("/api/rules").session(login(mvc, user2(), PASSWORD)))
                .andExpect(jsonPath("$.length()").value(0));
        mvc.perform(get("/api/imports").session(session))
                .andExpect(jsonPath("$[0].fileName").value("mai.xlsx"))
                .andExpect(jsonPath("$[0].created").value(2))
                .andExpect(jsonPath("$[0].rejected").value(1));
    }

    @Test
    void importingTheSameFileAgainCreatesNothing() throws Exception {
        long owner = user("deux-fois@test.local");
        MockHttpSession session = login(mvc, "deux-fois@test.local", PASSWORD);
        byte[] file = xlsx(ALL_COLUMNS, periodRow("B-1", "a@exemple.ml", "2027-05-03", "2027-05-07"));

        apply(session, "juin.xlsx", file).andExpect(jsonPath("$.created").value(1));
        apply(session, "juin.xlsx", file)
                .andExpect(jsonPath("$.created").value(0))
                .andExpect(jsonPath("$.unchanged").value(1));

        assertThat(rules.countByOwnerId(owner)).isEqualTo(1);
    }

    @Test
    void anExistingRowWithADifferentConfigurationIsNeverOverwritten() throws Exception {
        long owner = user("prudent@test.local");
        MockHttpSession session = login(mvc, "prudent@test.local", PASSWORD);
        apply(session, "v1.xlsx", xlsx(ALL_COLUMNS, periodRow("C-1", "a@exemple.ml", "2027-05-03", "2027-05-07")));
        String hashBefore = rules.findByOwnerIdAndBusinessId(owner, "C-1").orElseThrow().getConfigHash();

        apply(session, "v2.xlsx", xlsx(ALL_COLUMNS, periodRow("C-1", "autre@exemple.ml", "2027-05-03", "2027-05-07")))
                .andExpect(jsonPath("$.created").value(0))
                .andExpect(jsonPath("$.notApplied").value(1));

        var unchanged = rules.findByOwnerIdAndBusinessId(owner, "C-1").orElseThrow();
        assertThat(unchanged.getRecipientEmail()).isEqualTo("a@exemple.ml");
        assertThat(unchanged.getConfigHash()).isEqualTo(hashBefore);
        mvc.perform(get("/api/imports").session(session))
                .andExpect(jsonPath("$[0].summary", containsString("C-1")));
    }

    @Test
    void theSameFileIdBelongsToEachOwnerSeparately() throws Exception {
        long first = user("bureau-a@test.local");
        long second = user("bureau-b@test.local");
        byte[] file = xlsx(ALL_COLUMNS, periodRow("D-1", "a@exemple.ml", "2027-05-03", "2027-05-07"));

        apply(login(mvc, "bureau-a@test.local", PASSWORD), "a.xlsx", file).andExpect(jsonPath("$.created").value(1));
        apply(login(mvc, "bureau-b@test.local", PASSWORD), "b.xlsx", file).andExpect(jsonPath("$.created").value(1));

        assertThat(rules.countByOwnerId(first)).isEqualTo(1);
        assertThat(rules.countByOwnerId(second)).isEqualTo(1);
    }

    @Test
    void aFileWithMissingColumnsIsNotImported() throws Exception {
        long owner = user("incomplet@test.local");
        MockHttpSession session = login(mvc, "incomplet@test.local", PASSWORD);

        apply(session, "x.xlsx", xlsx(List.of("id", "email"), Map.of("id", "E-1", "email", "a@exemple.ml")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail", containsString("validation_quotidienne")));

        assertThat(rules.countByOwnerId(owner)).isZero();
        mvc.perform(get("/api/imports").session(session)).andExpect(jsonPath("$.length()").value(0));
    }

    /** Un autre utilisateur, qui ne doit voir aucune des lignes ci-dessus. */
    private String user2() {
        user("spectateur@test.local");
        return "spectateur@test.local";
    }

    private long user(String email) {
        return accounts.findByEmail(email)
                .orElseGet(() -> accounts.save(new UserAccount(email, passwordEncoder.encode(PASSWORD), Role.USER)))
                .getId();
    }

    private ResultActions apply(MockHttpSession session, String name, byte[] content) throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", name,
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", content);
        return mvc.perform(multipart("/api/imports").file(file).session(session).with(realCsrf(mvc)));
    }
}
