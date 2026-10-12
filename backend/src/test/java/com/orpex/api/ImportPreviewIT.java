package com.orpex.api;

import static com.orpex.CsrfSupport.realCsrf;
import static com.orpex.MockMvcSupport.login;
import static com.orpex.XlsxFiles.ALL_COLUMNS;
import static com.orpex.XlsxFiles.periodRow;
import static com.orpex.XlsxFiles.xlsx;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.orpex.TestcontainersConfiguration;
import com.orpex.account.Role;
import com.orpex.account.UserAccount;
import com.orpex.account.UserAccountRepository;
import com.orpex.core.calendar.SchedulingRule;
import com.orpex.core.importing.ConfigHash;
import com.orpex.core.importing.ImportedRule;
import com.orpex.core.importing.RuleStatus;
import com.orpex.rule.Rule;
import com.orpex.rule.RuleRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
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
class ImportPreviewIT {

    private static final String PASSWORD = "mot-de-passe-solide";

    @Autowired
    MockMvc mvc;

    @Autowired
    UserAccountRepository accounts;

    @Autowired
    RuleRepository rules;

    @Autowired
    PasswordEncoder passwordEncoder;

    private long ownerId;
    private MockHttpSession session;

    @BeforeEach
    void loginAsUser() throws Exception {
        ownerId = accounts.findByEmail("importeur@test.local")
                .orElseGet(() -> accounts.save(new UserAccount("importeur@test.local",
                        passwordEncoder.encode(PASSWORD), Role.USER)))
                .getId();
        session = login(mvc, "importeur@test.local", PASSWORD);
    }

    @Test
    void previewsValidAndRejectedRowsWithoutWritingAnything() throws Exception {
        Map<String, String> ambiguous = periodRow("P-2", "b@exemple.ml", "", "");
        ambiguous.put("mode_envoi", "ponctuel");
        ambiguous.put("date_envoi", "03/04");
        Map<String, String> once = periodRow("P-3", "c@exemple.ml", "", "");
        once.put("mode_envoi", "ponctuel");
        once.put("date_envoi", "2027-03-10");

        preview(xlsx(ALL_COLUMNS, periodRow("P-1", "a@exemple.ml", "2027-03-01", "2027-03-05"), ambiguous, once))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fileName").value("relances.xlsx"))
                .andExpect(jsonPath("$.missingColumns.length()").value(0))
                .andExpect(jsonPath("$.counts.toCreate").value(2))
                .andExpect(jsonPath("$.counts.rejected").value(1))
                .andExpect(jsonPath("$.counts.plannedSends").value(6))
                .andExpect(jsonPath("$.rows[0].rowNumber").value(2))
                .andExpect(jsonPath("$.rows[0].action").value("CREATE"))
                .andExpect(jsonPath("$.rows[0].interpreted.startDate").value("2027-03-01"))
                .andExpect(jsonPath("$.rows[0].sends.planned").value(5))
                .andExpect(jsonPath("$.rows[0].sends.first").value("2027-03-01"))
                .andExpect(jsonPath("$.rows[0].sends.last").value("2027-03-05"))
                .andExpect(jsonPath("$.rows[1].action").value("REJECTED"))
                .andExpect(jsonPath("$.rows[1].errors[0]", containsString("« 03/04 » illisible")))
                .andExpect(jsonPath("$.rows[1].interpreted").doesNotExist());

        assertThat(rules.countByOwnerId(ownerId)).as("l'aperçu n'écrit rien").isZero();
    }

    @Test
    void comparesEachRowWithTheOwnersExistingRules() throws Exception {
        Map<String, String> same = periodRow("E-1", "a@exemple.ml", "2027-04-05", "2027-04-09");
        Map<String, String> changed = periodRow("E-2", "a@exemple.ml", "2027-04-12", "2027-04-16");
        existing("E-1", ConfigHash.of(same));
        existing("E-2", ConfigHash.of(periodRow("E-2", "a@exemple.ml", "2027-04-12", "2027-04-13")));

        preview(xlsx(ALL_COLUMNS, same, changed))
                .andExpect(jsonPath("$.rows[0].action").value("UNCHANGED"))
                .andExpect(jsonPath("$.rows[1].action").value("UPDATE"))
                .andExpect(jsonPath("$.counts.unchanged").value(1))
                .andExpect(jsonPath("$.counts.toUpdate").value(1));
    }

    @Test
    void aFileWithoutTheRequiredColumnsIsRefusedAsAWhole() throws Exception {
        preview(xlsx(List.of("id", "email"), Map.of("id", "X", "email", "x@exemple.ml")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.missingColumns", org.hamcrest.Matchers.hasItem("validation_quotidienne")))
                .andExpect(jsonPath("$.rows.length()").value(0));
    }

    @Test
    void anUnreadableFileGetsAClearMessage() throws Exception {
        preview("id;email\n1;a@b.c".getBytes())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Le fichier n'est pas un classeur Excel .xlsx lisible."));
    }

    private void existing(String businessId, String hash) {
        ImportedRule rule = new ImportedRule(businessId, "a@exemple.ml", "Awa", null, null, "Relance facture",
                "Bonjour {{prenom}}", SchedulingRule.period(LocalDate.parse("2027-04-05"),
                LocalDate.parse("2027-04-09"), true), LocalTime.of(9, 0), false, RuleStatus.ACTIVE);
        rules.save(Rule.fromImport(rule, hash, ownerId, Instant.now()));
    }

    private ResultActions preview(byte[] content) throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "relances.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", content);
        return mvc.perform(multipart("/api/imports/preview").file(file).session(session).with(realCsrf(mvc)));
    }
}
