package com.orpex;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Démarre l'application complète sur une vraie base : détecte une configuration Spring cassée, une migration Flyway
 * qui échoue ou une entité incompatible avec le schéma (ddl-auto=validate).
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class OrpexApplicationIT {

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    Clock clock;

    @Test
    void contextStartsAndAllMigrationsSucceed() {
        Integer failed = jdbc.queryForObject(
                "select count(*) from flyway_schema_history where not success", Integer.class);
        Integer applied = jdbc.queryForObject(
                "select count(*) from flyway_schema_history where success", Integer.class);

        assertThat(failed).isZero();
        assertThat(applied).isPositive();
    }

    @Test
    void applicationClockUsesTheInstallationTimezoneOfThePilot() {
        assertThat(clock.getZone()).isEqualTo(ZoneId.of("Africa/Bamako"));
    }
}
