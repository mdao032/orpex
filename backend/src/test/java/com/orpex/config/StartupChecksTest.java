package com.orpex.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class StartupChecksTest {

    @Test
    void aCompleteHttpsConfigurationRaisesNothing() {
        MockEnvironment env = new MockEnvironment().withProperty("orpex.secret-key", "clé")
                .withProperty("orpex.app-url", "https://orpex.banque.local")
                .withProperty("server.forward-headers-strategy", "native")
                .withProperty("server.servlet.session.cookie.secure", "true");
        assertThat(new StartupChecks(env).problems()).isEmpty();
    }

    @Test
    void missingKeyPlainHttpLinksAndInsecureCookiesAreReported() {
        MockEnvironment env = new MockEnvironment().withProperty("orpex.app-url", "http://orpex.banque.local")
                .withProperty("server.ssl.enabled", "true");
        assertThat(new StartupChecks(env).problems()).hasSize(3)
                .anySatisfy(p -> assertThat(p).contains("ORPEX_SECRET_KEY"))
                .anySatisfy(p -> assertThat(p).contains("pas en HTTPS"))
                .anySatisfy(p -> assertThat(p).contains("ORPEX_COOKIE_SECURE"));
    }
}
