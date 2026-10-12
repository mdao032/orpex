package com.orpex.account;

import static com.orpex.CsrfSupport.realCsrf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.orpex.TestcontainersConfiguration;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** Verrouillage après 5 échecs pendant 15 min (valeurs de application.properties), déconnexion explicite. */
@SpringBootTest(properties = {
        "orpex.bootstrap-admin.email= Admin@Test.Local ",
        "orpex.bootstrap-admin.password=secret-de-test"
})
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class LoginProtectionIT {

    private static final String PASSWORD = "bon-mot-de-passe";

    @Autowired
    MockMvc mvc;

    @Autowired
    UserAccountRepository accounts;

    @Autowired
    PasswordEncoder passwordEncoder;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void fiveFailuresLockTheAccountEvenAgainstTheRightPassword() throws Exception {
        createUser("verrou@test.local");

        for (int i = 0; i < 5; i++) {
            login("verrou@test.local", "faux").andExpect(status().isUnauthorized());
        }
        UserAccount locked = account("verrou@test.local");
        assertThat(locked.getLockedUntil())
                .isBetween(Instant.now().plus(Duration.ofMinutes(14)), Instant.now().plus(Duration.ofMinutes(16)));

        login("verrou@test.local", PASSWORD).andExpect(status().isUnauthorized());
        login("verrou@test.local", "faux").andExpect(status().isUnauthorized());
        assertThat(account("verrou@test.local").getLockedUntil())
                .as("une tentative pendant le verrouillage ne le prolonge pas")
                .isEqualTo(locked.getLockedUntil());
    }

    @Test
    void loginWorksAgainOnceTheLockHasExpired() throws Exception {
        createUser("expire@test.local");
        for (int i = 0; i < 5; i++) {
            login("expire@test.local", "faux");
        }
        jdbc.update("update users set locked_until = now() - interval '1 minute' where email = ?", "expire@test.local");

        login("expire@test.local", PASSWORD).andExpect(status().isOk());

        UserAccount account = account("expire@test.local");
        assertThat(account.getLockedUntil()).isNull();
        assertThat(account.getFailedAttempts()).isZero();
        assertThat(account.getLastLoginAt()).isNotNull();
    }

    @Test
    void successfulLoginResetsTheFailureCounter() throws Exception {
        createUser("compteur@test.local");
        for (int i = 0; i < 4; i++) {
            login("compteur@test.local", "faux");
        }
        assertThat(account("compteur@test.local").getFailedAttempts()).isEqualTo(4);

        login("compteur@test.local", PASSWORD).andExpect(status().isOk());
        login("compteur@test.local", "faux").andExpect(status().isUnauthorized());

        assertThat(account("compteur@test.local").getFailedAttempts()).isEqualTo(1);
        assertThat(account("compteur@test.local").getLockedUntil()).isNull();
    }

    @Test
    void failuresOnAnUnknownEmailAreRejectedWithoutError() throws Exception {
        for (int i = 0; i < 6; i++) {
            login("personne@test.local", "faux").andExpect(status().isUnauthorized());
        }
        assertThat(accounts.findByEmail("personne@test.local")).isEmpty();
    }

    @Test
    void logoutEndsTheSession() throws Exception {
        createUser("sortie@test.local");
        MockHttpSession session = (MockHttpSession) login("sortie@test.local", PASSWORD)
                .andExpect(status().isOk())
                .andReturn().getRequest().getSession(false);
        mvc.perform(get("/api/me").session(session)).andExpect(status().isOk());

        mvc.perform(post("/logout").session(session).with(realCsrf(mvc))).andExpect(status().isOk());

        mvc.perform(get("/api/me").session(session)).andExpect(status().isUnauthorized());
    }

    private void createUser(String email) {
        accounts.save(new UserAccount(email, passwordEncoder.encode(PASSWORD), Role.USER));
    }

    private UserAccount account(String email) {
        return accounts.findByEmail(email).orElseThrow();
    }

    private ResultActions login(String email, String password) throws Exception {
        return mvc.perform(post("/login").param("username", email).param("password", password).with(realCsrf(mvc)));
    }
}
