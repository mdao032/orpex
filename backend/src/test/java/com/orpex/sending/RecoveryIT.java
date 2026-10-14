package com.orpex.sending;

import static com.orpex.MockMvcSupport.login;
import static com.orpex.MockMvcSupport.send;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
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
import com.orpex.core.mail.MailGateway;
import com.orpex.rule.ActivationService;
import com.orpex.rule.Occurrence;
import com.orpex.rule.OccurrenceRepository;
import com.orpex.rule.OccurrenceState;
import com.orpex.rule.Rule;
import com.orpex.rule.RuleRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** Arrêt pendant l'envoi : « à vérifier », jamais renvoyé ; l'utilisateur décide. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class RecoveryIT {

    private static final String PASSWORD = "mot-de-passe-solide";

    @MockitoBean
    MailGateway gateway;

    @Autowired
    MockMvc mvc;

    @Autowired
    SendWorker worker;

    @Autowired
    ActivationService activation;

    @Autowired
    RuleRepository rules;

    @Autowired
    OccurrenceRepository occurrences;

    @Autowired
    UserAccountRepository accounts;

    @Autowired
    PasswordEncoder passwordEncoder;

    @Autowired
    JdbcTemplate jdbc;

    private long owner;
    private MockHttpSession session;

    @BeforeEach
    void setUp() throws Exception {
        reset(gateway);
        owner = account("reprise@test.local");
        account("voisin-reprise@test.local");
        session = login(mvc, "reprise@test.local", PASSWORD);
    }

    @Test
    void aSendInterruptedByAStopIsToCheckAndNeverSentAgain() throws Exception {
        long interrupted = sendInState("SENDING", "now() - interval '11 minutes'");
        long inProgress = sendInState("SENDING", "now() - interval '1 minute'");

        worker.processDueSends();

        Occurrence toCheck = occurrences.findById(interrupted).orElseThrow();
        assertThat(toCheck.getState()).isEqualTo(OccurrenceState.TO_CHECK);
        assertThat(toCheck.getLastError()).startsWith("envoi interrompu (arrêt de l'application)");
        assertThat(occurrences.findById(inProgress).orElseThrow().getState())
                .as("envoi en cours depuis peu : laissé tranquille").isEqualTo(OccurrenceState.SENDING);
        worker.processDueSends();
        verify(gateway, never()).send(any());
    }

    @Test
    void theOwnerDecidesWhetherItLeft() throws Exception {
        long left = sendInState("TO_CHECK", "now()");
        long notLeft = sendInState("TO_CHECK", "now()");

        send(mvc, post(resolve(left)).session(session), "{\"sent\":true}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("SENT"));
        send(mvc, post(resolve(notLeft)).session(session), "{\"sent\":false}")
                .andExpect(jsonPath("$.state").value("FAILED"))
                .andExpect(jsonPath("$.lastError").value("non parti (confirmé par l'utilisateur après vérification)"));

        send(mvc, post(resolve(left)).session(session), "{\"sent\":false}")
                .andExpect(status().isConflict());
        long other = sendInState("TO_CHECK", "now()");
        send(mvc, post(resolve(other)).session(login(mvc, "voisin-reprise@test.local", PASSWORD)), "{\"sent\":true}")
                .andExpect(status().isNotFound());
        verify(gateway, never()).send(any());
    }

    private String resolve(long occurrenceId) {
        long ruleId = occurrences.findById(occurrenceId).orElseThrow().getRuleId();
        return "/api/rules/" + ruleId + "/sends/" + occurrenceId + "/resolve";
    }

    /** Envoi d'une relance activée, placé dans l'état donné ; {@code since} : dernière modification. */
    private long sendInState(String state, String since) {
        ImportedRule imported = new ImportedRule("R-" + System.nanoTime(), "dest@exemple.ml", null, null, null,
                "Relance", "Bonjour", SchedulingRule.once(LocalDate.parse("2027-12-01"), false), LocalTime.of(9, 0),
                false, RuleStatus.ACTIVE);
        long ruleId = rules.save(Rule.fromImport(imported, "a".repeat(64), owner, Instant.now())).getId();
        activation.activate(owner, ruleId);
        long id = occurrences.findAllByRuleIdOrderByScheduledDateAsc(ruleId).getFirst().getId();
        jdbc.update("update occurrences set state = ?, scheduled_at = now() - interval '30 minutes', updated_at = "
                + since + " where id = ?", state, id);
        return id;
    }

    private long account(String email) {
        return accounts.findByEmail(email)
                .orElseGet(() -> accounts.save(new UserAccount(email, passwordEncoder.encode(PASSWORD), Role.USER)))
                .getId();
    }
}
