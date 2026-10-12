package com.orpex.rule;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.orpex.TestcontainersConfiguration;
import com.orpex.account.Role;
import com.orpex.account.UserAccount;
import com.orpex.account.UserAccountRepository;
import com.orpex.core.calendar.SchedulingRule;
import com.orpex.core.importing.ImportedRule;
import com.orpex.core.importing.RuleStatus;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;

@SpringBootTest(properties = {
        "orpex.bootstrap-admin.email= Admin@Test.Local ",
        "orpex.bootstrap-admin.password=secret-de-test"
})
@Import(TestcontainersConfiguration.class)
class RuleIT {

    private static final String HASH = "a".repeat(64);

    @Autowired
    RuleRepository rules;

    @Autowired
    ImportRecordRepository imports;

    @Autowired
    UserAccountRepository accounts;

    @Test
    void aRuleIsStoredWithItsScheduleAndReadOnlyByItsOwner() {
        long owner = user("proprietaire@test.local");
        long other = user("voisin@test.local");
        SchedulingRule schedule = SchedulingRule.period(LocalDate.parse("2026-11-02"), LocalDate.parse("2026-11-06"), true);

        Rule saved = rules.save(Rule.fromImport(imported("DOS-1", schedule), HASH, owner, now()));

        Rule read = rules.findByOwnerIdAndBusinessId(owner, "DOS-1").orElseThrow();
        assertThat(read.scheduling()).isEqualTo(schedule);
        assertThat(read.getSendTime()).isEqualTo(LocalTime.of(9, 0));
        assertThat(read.getStatus()).isEqualTo(RuleStatus.ACTIVE);
        assertThat(read.getCaseReference()).as("vide dans le fichier : null en base").isNull();
        assertThat(read.getConfigHash()).isEqualTo(HASH);

        assertThat(rules.findByIdAndOwnerId(saved.getId(), other)).isEmpty();
        assertThat(rules.findByOwnerIdAndBusinessId(other, "DOS-1")).isEmpty();
        assertThat(rules.findAllByOwnerIdOrderByBusinessIdAsc(other)).isEmpty();
    }

    @Test
    void theFileIdIsUniquePerOwnerOnly() {
        long first = user("premier@test.local");
        long second = user("second@test.local");
        SchedulingRule schedule = SchedulingRule.once(LocalDate.parse("2026-11-02"), false);
        rules.save(Rule.fromImport(imported("DOS-2", schedule), HASH, first, now()));

        rules.save(Rule.fromImport(imported("DOS-2", schedule), HASH, second, now()));

        assertThatThrownBy(() -> rules.save(Rule.fromImport(imported("DOS-2", schedule), HASH, first, now())))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void importsAreRecordedAndListedForTheirAuthorOnly() {
        long author = user("auteur@test.local");
        imports.save(new ImportRecord(author, "relances-novembre.xlsx", 3, 1, 5, 2, null, now()));

        assertThat(imports.findAllByUserIdOrderByCreatedAtDesc(author))
                .singleElement()
                .satisfies(record -> {
                    assertThat(record.getFileName()).isEqualTo("relances-novembre.xlsx");
                    assertThat(record.getRejectedCount()).isEqualTo(2);
                });
        assertThat(imports.findAllByUserIdOrderByCreatedAtDesc(user("autre@test.local"))).isEmpty();
    }

    private static ImportedRule imported(String id, SchedulingRule schedule) {
        return new ImportedRule(id, "destinataire@exemple.ml", "Awa", "Traoré", "", "Relance", "Bonjour {{prenom}}",
                schedule, LocalTime.of(9, 0), false, RuleStatus.ACTIVE);
    }

    private long user(String email) {
        return accounts.findByEmail(email)
                .orElseGet(() -> accounts.save(new UserAccount(email, "{noop}x", Role.USER)))
                .getId();
    }

    private static Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.MICROS);
    }
}
