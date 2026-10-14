package com.orpex.sending;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;

import com.orpex.TestcontainersConfiguration;
import com.orpex.account.Role;
import com.orpex.account.UserAccount;
import com.orpex.account.UserAccountRepository;
import com.orpex.core.calendar.SchedulingRule;
import com.orpex.core.importing.ImportedRule;
import com.orpex.core.importing.RuleStatus;
import com.orpex.core.mail.MailGateway;
import com.orpex.core.mail.MailSendException;
import com.orpex.rule.ActivationService;
import com.orpex.rule.Occurrence;
import com.orpex.rule.OccurrenceRepository;
import com.orpex.rule.OccurrenceState;
import com.orpex.rule.Rule;
import com.orpex.rule.RuleRepository;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/** Nouvelles tentatives (spécification §10) : jusqu'à 3 après un échec temporaire, à +1, +5 et +15 minutes. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class SendRetryIT {

    private static final MailSendException TEMPORARY =
            new MailSendException("serveur de messagerie injoignable : Connection refused", true, null);
    private static final MailSendException FINAL =
            new MailSendException("refusé par le serveur : 550 5.1.1 mailbox unavailable", false, null);

    @MockitoBean
    MailGateway gateway;

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
    JdbcTemplate jdbc;

    private long owner;

    @BeforeEach
    void setUp() {
        reset(gateway);
        owner = accounts.findByEmail("tentatives@test.local")
                .orElseGet(() -> accounts.save(new UserAccount("tentatives@test.local", "{noop}x", Role.USER)))
                .getId();
    }

    @Test
    void temporaryFailuresAreRetriedThreeTimesThenTheSendFails() throws Exception {
        doThrow(TEMPORARY).when(gateway).send(any());
        long send = dueSend(false);

        assertThat(worker.processDueSends().retried()).isEqualTo(1);
        Occurrence first = occurrences.findById(send).orElseThrow();
        assertThat(first.getState()).isEqualTo(OccurrenceState.PLANNED);
        assertThat(first.getAttempts()).isEqualTo(1);
        assertThat(first.getLastError()).isEqualTo(TEMPORARY.getMessage());
        assertThat(Duration.between(Instant.now(), first.getNextAttemptAt()))
                .isBetween(Duration.ofSeconds(50), Duration.ofSeconds(70));
        assertThat(worker.processDueSends().retried()).as("la tentative suivante n'est pas encore due").isZero();

        for (int attempt = 2; attempt <= 3; attempt++) {
            makeRetryDue(send);
            assertThat(worker.processDueSends().retried()).isEqualTo(1);
        }
        assertThat(Duration.between(Instant.now(), occurrences.findById(send).orElseThrow().getNextAttemptAt()))
                .as("3e nouvelle tentative : +15 min").isBetween(Duration.ofMinutes(14), Duration.ofMinutes(16));

        makeRetryDue(send);
        assertThat(worker.processDueSends().failed()).isEqualTo(1);
        Occurrence failed = occurrences.findById(send).orElseThrow();
        assertThat(failed.getState()).isEqualTo(OccurrenceState.FAILED);
        assertThat(failed.getAttempts()).isEqualTo(4);
        assertThat(failed.getLastError()).endsWith("(4 tentatives)");
        assertThat(failed.getNextAttemptAt()).isNull();
    }

    @Test
    void aFinalFailureIsNotRetried() throws Exception {
        doThrow(FINAL).when(gateway).send(any());
        long send = dueSend(false);

        assertThat(worker.processDueSends().failed()).isEqualTo(1);

        Occurrence failed = occurrences.findById(send).orElseThrow();
        assertThat(failed.getState()).isEqualTo(OccurrenceState.FAILED);
        assertThat(failed.getAttempts()).isEqualTo(1);
        assertThat(failed.getLastError()).isEqualTo(FINAL.getMessage());
    }

    @Test
    void aRetryLaterThanTheLatenessLimitStillLeavesAndAValidatedSendStaysValidated() throws Exception {
        doThrow(TEMPORARY).doNothing().when(gateway).send(any());
        long send = dueSend(true);
        jdbc.update("update occurrences set state = 'VALIDATED' where id = ?", send);

        worker.processDueSends();
        assertThat(occurrences.findById(send).orElseThrow().getState()).isEqualTo(OccurrenceState.VALIDATED);

        jdbc.update("update occurrences set scheduled_at = now() - interval '1 hour', "
                + "next_attempt_at = now() - interval '1 second' where id = ?", send);
        assertThat(worker.processDueSends().sent()).isEqualTo(1);

        Occurrence sent = occurrences.findById(send).orElseThrow();
        assertThat(sent.getState()).isEqualTo(OccurrenceState.SENT);
        assertThat(sent.getAttempts()).isEqualTo(2);
        assertThat(sent.getLastError()).isNull();
        assertThat(sent.getNextAttemptAt()).isNull();
    }

    /** Une relance activée dont le premier envoi est dû depuis une minute ; renvoie l'id de cet envoi. */
    private long dueSend(boolean dailyValidation) {
        ImportedRule imported = new ImportedRule("T-" + System.nanoTime(), "dest@exemple.ml", null, null, null,
                "Relance", "Bonjour", SchedulingRule.once(LocalDate.parse("2027-11-02"), false), LocalTime.of(9, 0),
                dailyValidation, RuleStatus.ACTIVE);
        long ruleId = rules.save(Rule.fromImport(imported, "a".repeat(64), owner, Instant.now())).getId();
        activation.activate(owner, ruleId);
        jdbc.update("update occurrences set scheduled_at = now() - interval '1 minute' where rule_id = ?", ruleId);
        return occurrences.findAllByRuleIdOrderByScheduledDateAsc(ruleId).getFirst().getId();
    }

    private void makeRetryDue(long send) {
        jdbc.update("update occurrences set next_attempt_at = now() - interval '1 second' where id = ?", send);
    }
}
