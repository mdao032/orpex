package com.orpex.sending;

import static org.assertj.core.api.Assertions.assertThat;

import com.orpex.TestcontainersConfiguration;
import com.orpex.account.Role;
import com.orpex.account.UserAccount;
import com.orpex.account.UserAccountRepository;
import com.orpex.core.calendar.SchedulingRule;
import com.orpex.core.importing.ImportedRule;
import com.orpex.core.importing.RuleStatus;
import com.orpex.rule.ActivationService;
import com.orpex.rule.Occurrence;
import com.orpex.rule.OccurrenceRepository;
import com.orpex.rule.OccurrenceState;
import com.orpex.rule.Rule;
import com.orpex.rule.RuleRepository;
import com.orpex.rule.SendingControlService;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import tools.jackson.databind.json.JsonMapper;

/** Le worker contre une vraie base et un vrai serveur de messagerie (Mailpit). */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class SendWorkerIT {

    private static final GenericContainer<?> MAILPIT = new GenericContainer<>("axllent/mailpit:v1.31.5")
            .withExposedPorts(1025, 8025)
            .withEnv("MP_SMTP_ALLOWED_RECIPIENTS", "@exemple\\.ml$")
            .withEnv("MP_SMTP_DISABLE_RDNS", "true")
            .waitingFor(Wait.forHttp("/livez").forPort(8025));

    static {
        MAILPIT.start();
    }

    @DynamicPropertySource
    static void mail(DynamicPropertyRegistry registry) {
        registry.add("spring.mail.host", MAILPIT::getHost);
        registry.add("spring.mail.port", () -> MAILPIT.getMappedPort(1025));
    }

    /** Du lundi 4 au vendredi 8 octobre 2027 : 5 envois à 09:00. */
    private static final SchedulingRule WEEK = SchedulingRule.period(LocalDate.parse("2027-10-04"),
            LocalDate.parse("2027-10-08"), true);

    @Autowired
    SendWorker worker;

    @Autowired
    ActivationService activation;

    @Autowired
    SendingControlService sendingControl;

    @Autowired
    RuleRepository rules;

    @Autowired
    OccurrenceRepository occurrences;

    @Autowired
    UserAccountRepository accounts;

    @Autowired
    JdbcTemplate jdbc;

    private long owner;
    private long adminId;
    private final AtomicInteger counter = new AtomicInteger();

    @BeforeEach
    void setUp() throws Exception {
        owner = account("expediteur@test.local", Role.USER);
        adminId = account("superviseur@test.local", Role.ADMIN);
        HttpClient.newHttpClient().send(HttpRequest.newBuilder(mailpit("/api/v1/messages")).DELETE().build(),
                HttpResponse.BodyHandlers.discarding());
    }

    @Test
    void aDueSendLeavesOnceWithItsVariablesReplaced() throws Exception {
        long id = activatedRule("dest@exemple.ml", "Relance {{reference_dossier}}", "Bonjour {{prenom}}, {{montant}} FCFA.",
                Map.of("montant", "125 000"), false);
        makeDue(id, "2027-10-04", "2027-10-05");

        SendWorker.Pass first = worker.processDueSends();
        SendWorker.Pass second = worker.processDueSends();

        assertThat(first.sent()).isEqualTo(2);
        assertThat(second.sent()).as("déjà envoyés : rien ne repart").isZero();
        assertThat(mailCount()).isEqualTo(2);
        var latest = JsonMapper.builder().build().readTree(get("/api/v1/messages")).get("messages").get(0);
        assertThat(latest.get("Subject").asString()).isEqualTo("Relance FAC-" + id);
        assertThat(states(id)).containsExactly(OccurrenceState.SENT, OccurrenceState.SENT, OccurrenceState.PLANNED,
                OccurrenceState.PLANNED, OccurrenceState.PLANNED);
        Occurrence sent = occurrences.findAllByRuleIdOrderByScheduledDateAsc(id).getFirst();
        assertThat(sent.getSentAt()).isNotNull();
        assertThat(sent.getAttempts()).isEqualTo(1);
    }

    @Test
    void aSendTooLateIsMissedNotSent() throws Exception {
        long id = activatedRule("dest@exemple.ml", "Relance", "Bonjour", Map.of(), false);
        jdbc.update("update occurrences set scheduled_at = now() - interval '2 hours' where rule_id = ? "
                + "and scheduled_date = '2027-10-04'", id);

        worker.processDueSends();

        assertThat(states(id).getFirst()).isEqualTo(OccurrenceState.MISSED);
        assertThat(mailCount()).isZero();
    }

    @Test
    void nothingLeavesForAPausedRuleAWaitingValidationOrAGlobalPause() throws Exception {
        long paused = activatedRule("dest@exemple.ml", "Relance", "Bonjour", Map.of(), false);
        sendingControl.setRulePause(owner, paused, true);
        long awaiting = activatedRule("dest@exemple.ml", "Relance", "Bonjour", Map.of(), true);
        makeDue(paused, "2027-10-04");
        makeDue(awaiting, "2027-10-04");

        worker.processDueSends();
        assertThat(states(paused).getFirst()).isEqualTo(OccurrenceState.PLANNED);
        assertThat(states(awaiting).getFirst()).isEqualTo(OccurrenceState.AWAITING_VALIDATION);

        long normal = activatedRule("dest@exemple.ml", "Relance", "Bonjour", Map.of(), false);
        makeDue(normal, "2027-10-04");
        sendingControl.setGlobalPause(true, adminId);
        try {
            assertThat(worker.processDueSends().sent()).isZero();
        } finally {
            sendingControl.setGlobalPause(false, adminId);
        }
        assertThat(mailCount()).isZero();
    }

    @Test
    void aMissingVariableOrARefusedRecipientFailsWithTheReason() throws Exception {
        long incomplete = activatedRule("dest@exemple.ml", "Relance", "Montant : {{montant}}", Map.of(), false);
        long refused = activatedRule("inconnu@ailleurs.com", "Relance", "Bonjour", Map.of(), false);
        makeDue(incomplete, "2027-10-04");
        makeDue(refused, "2027-10-04");

        SendWorker.Pass pass = worker.processDueSends();

        assertThat(pass.failed()).isEqualTo(2);
        Occurrence missing = occurrences.findAllByRuleIdOrderByScheduledDateAsc(incomplete).getFirst();
        assertThat(missing.getState()).isEqualTo(OccurrenceState.FAILED);
        assertThat(missing.getLastError()).isEqualTo("message incomplet : la variable {{montant}} n'a pas de valeur");
        Occurrence rejected = occurrences.findAllByRuleIdOrderByScheduledDateAsc(refused).getFirst();
        assertThat(rejected.getState()).isEqualTo(OccurrenceState.FAILED);
        assertThat(rejected.getLastError()).startsWith("refusé par le serveur : 550");
        assertThat(mailCount()).isZero();
    }

    @Test
    void twoWorkersAtTheSameTimeNeverSendTheSameSendTwice() throws Exception {
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            long id = activatedRule("dest@exemple.ml", "Relance", "Bonjour", Map.of(), false);
            makeDue(id, "2027-10-04", "2027-10-05", "2027-10-06", "2027-10-07", "2027-10-08");
            ids.add(id);
        }

        var first = CompletableFuture.supplyAsync(worker::processDueSends);
        var second = CompletableFuture.supplyAsync(worker::processDueSends);
        int sent = first.get().sent() + second.get().sent();

        assertThat(sent).isEqualTo(20);
        assertThat(mailCount()).isEqualTo(20);
        ids.forEach(id -> assertThat(states(id)).containsOnly(OccurrenceState.SENT));
    }

    private long activatedRule(String to, String subject, String body, Map<String, String> extra, boolean validation) {
        String businessId = "W-" + counter.incrementAndGet() + "-" + System.nanoTime();
        ImportedRule imported = new ImportedRule(businessId, to, "Awa", null, null, subject, body, WEEK,
                LocalTime.of(9, 0), validation, RuleStatus.ACTIVE);
        Rule rule = rules.save(Rule.fromImport(imported, "a".repeat(64), extra, owner, Instant.now()));
        jdbc.update("update rules set case_reference = 'FAC-' || id where id = ?", rule.getId());
        activation.activate(owner, rule.getId());
        return rule.getId();
    }

    /** L'heure des envois de ces jours est atteinte depuis une minute. */
    private void makeDue(long ruleId, String... days) {
        for (String day : days) {
            jdbc.update("update occurrences set scheduled_at = now() - interval '1 minute' "
                    + "where rule_id = ? and scheduled_date = ?::date", ruleId, day);
        }
    }

    private List<OccurrenceState> states(long ruleId) {
        return occurrences.findAllByRuleIdOrderByScheduledDateAsc(ruleId).stream().map(Occurrence::getState).toList();
    }

    private long account(String email, Role role) {
        return accounts.findByEmail(email)
                .orElseGet(() -> accounts.save(new UserAccount(email, "{noop}x", role)))
                .getId();
    }

    private static int mailCount() throws Exception {
        return JsonMapper.builder().build().readTree(get("/api/v1/messages")).get("total").asInt();
    }

    private static String get(String path) throws Exception {
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(mailpit(path)).build(),
                HttpResponse.BodyHandlers.ofString()).body();
    }

    private static URI mailpit(String path) {
        return URI.create("http://" + MAILPIT.getHost() + ":" + MAILPIT.getMappedPort(8025) + path);
    }
}
