package com.orpex;

import static org.assertj.core.api.Assertions.assertThat;

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

    @Test
    void contextStartsAndAllMigrationsSucceed() {
        Integer failed = jdbc.queryForObject(
                "select count(*) from flyway_schema_history where not success", Integer.class);
        Integer applied = jdbc.queryForObject(
                "select count(*) from flyway_schema_history where success", Integer.class);

        assertThat(failed).isZero();
        assertThat(applied).isPositive();
    }
}
