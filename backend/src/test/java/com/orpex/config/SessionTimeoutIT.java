package com.orpex.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.orpex.TestcontainersConfiguration;
import org.apache.catalina.Context;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.tomcat.TomcatWebServer;
import org.springframework.boot.web.server.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.annotation.Import;

/** Vérifie sur le vrai serveur Tomcat que la session expire après 30 minutes d'inactivité (spécification §3). */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class SessionTimeoutIT {

    @Autowired
    ServletWebServerApplicationContext context;

    @Test
    void sessionsExpireAfterThirtyMinutesOfInactivity() {
        TomcatWebServer server = (TomcatWebServer) context.getWebServer();
        Context webapp = (Context) server.getTomcat().getHost().findChildren()[0];

        assertThat(webapp.getSessionTimeout()).as("minutes").isEqualTo(30);
    }
}
