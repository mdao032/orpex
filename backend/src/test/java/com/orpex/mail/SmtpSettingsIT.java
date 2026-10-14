package com.orpex.mail;

import static com.orpex.MockMvcSupport.login;
import static com.orpex.MockMvcSupport.send;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.orpex.TestcontainersConfiguration;
import com.orpex.account.Role;
import com.orpex.account.UserAccount;
import com.orpex.account.UserAccountRepository;
import com.orpex.core.mail.MailGateway;
import com.orpex.core.mail.MailMessage;
import com.orpex.core.mail.MailSendException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import tools.jackson.databind.json.JsonMapper;

/** Relais configuré à l'écran : mot de passe chiffré, jamais renvoyé, déchiffré pour s'authentifier. */
@SpringBootTest(properties = "orpex.secret-key=9TQpPMTx2Mavnc298//hI+jtMp0dsix7w0mO4uLJw4o=")
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class SmtpSettingsIT {

    private static final String RELAY_PASSWORD = "Mot-de-passe-du-relais-42";
    private static final String PASSWORD = "mot-de-passe-solide";

    /** Relais qui exige une authentification. */
    private static final GenericContainer<?> RELAY = new GenericContainer<>("axllent/mailpit:v1.31.5")
            .withExposedPorts(1025, 8025)
            .withEnv("MP_SMTP_AUTH", "relais:" + RELAY_PASSWORD)
            .withEnv("MP_SMTP_AUTH_ALLOW_INSECURE", "true")
            .withEnv("MP_SMTP_DISABLE_RDNS", "true")
            .waitingFor(Wait.forHttp("/livez").forPort(8025));

    static {
        RELAY.start();
    }

    /** La configuration du fichier pointe vers un port fermé : seul le relais de l'écran peut fonctionner. */
    @DynamicPropertySource
    static void unusableFileConfiguration(DynamicPropertyRegistry registry) {
        registry.add("spring.mail.host", () -> "localhost");
        registry.add("spring.mail.port", () -> 1);
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    MailGateway gateway;

    @Autowired
    MailSenderProvider senders;

    @Autowired
    UserAccountRepository accounts;

    @Autowired
    PasswordEncoder passwordEncoder;

    @Autowired
    JdbcTemplate jdbc;

    private MockHttpSession admin;

    @BeforeEach
    void loginAsAdmin() throws Exception {
        account("smtp-admin@test.local", Role.ADMIN);
        admin = login(mvc, "smtp-admin@test.local", PASSWORD);
    }

    @Test
    void theRelayConfiguredOnScreenIsUsedWithItsEncryptedPassword() throws Exception {
        save("{\"host\":\"" + RELAY.getHost() + "\",\"port\":" + RELAY.getMappedPort(1025) + ","
                + "\"encryption\":\"NONE\",\"username\":\"relais\",\"password\":\"" + RELAY_PASSWORD + "\","
                + "\"fromAddress\":\"relances@banque.ml\",\"fromName\":\"Banque - Relances\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.configured").value(true))
                .andExpect(jsonPath("$.passwordSet").value(true))
                .andExpect(content().string(org.hamcrest.Matchers.not(containsString(RELAY_PASSWORD))));

        byte[] stored = jdbc.queryForObject("select password_encrypted from smtp_settings", byte[].class);
        assertThat(new String(stored, StandardCharsets.ISO_8859_1)).doesNotContain(RELAY_PASSWORD);
        mvc.perform(get("/api/admin/smtp").session(admin))
                .andExpect(jsonPath("$.username").value("relais"))
                .andExpect(content().string(org.hamcrest.Matchers.not(containsString(RELAY_PASSWORD))));

        gateway.send(new MailMessage(senders.current().from(), "dest@exemple.ml", "Relance", "Bonjour"));
        var mail = JsonMapper.builder().build().readTree(mailpit("/api/v1/messages")).get("messages").get(0);
        assertThat(mail.get("From").get("Address").asString()).isEqualTo("relances@banque.ml");
        assertThat(mail.get("From").get("Name").asString()).isEqualTo("Banque - Relances");

        save("{\"host\":\"" + RELAY.getHost() + "\",\"port\":" + RELAY.getMappedPort(1025) + ","
                + "\"encryption\":\"NONE\",\"username\":\"relais\",\"password\":\"\","
                + "\"fromAddress\":\"relances@banque.ml\"}")
                .andExpect(jsonPath("$.passwordSet").value(true));
        gateway.send(new MailMessage(senders.current().from(), "dest@exemple.ml", "Relance 2", "Bonjour"));

        save("{\"host\":\"" + RELAY.getHost() + "\",\"port\":" + RELAY.getMappedPort(1025) + ","
                + "\"encryption\":\"NONE\",\"username\":\"relais\",\"password\":\"faux\","
                + "\"fromAddress\":\"relances@banque.ml\"}");
        assertThatThrownBy(() -> gateway.send(
                new MailMessage(senders.current().from(), "dest@exemple.ml", "Relance 3", "Bonjour")))
                .isInstanceOfSatisfying(MailSendException.class, e -> assertThat(e.isRetryable()).isFalse());
    }

    @Test
    void invalidValuesAreRefusedAndOnlyAdministratorsHaveAccess() throws Exception {
        save("{\"host\":\"relais.banque.ml\",\"port\":70000,\"encryption\":\"STARTTLS\","
                + "\"fromAddress\":\"relances@banque.ml\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Le port doit être compris entre 1 et 65535."));
        save("{\"host\":\"relais.banque.ml\",\"port\":587,\"encryption\":\"STARTTLS\","
                + "\"fromAddress\":\"relances@banque.ml\",\"caCertificate\":\"pas un certificat\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail", containsString("Certificat d'autorité illisible")));

        account("smtp-user@test.local", Role.USER);
        mvc.perform(get("/api/admin/smtp").session(login(mvc, "smtp-user@test.local", PASSWORD)))
                .andExpect(status().isForbidden());
    }

    private org.springframework.test.web.servlet.ResultActions save(String json) throws Exception {
        return send(mvc, put("/api/admin/smtp").session(admin), json);
    }

    private void account(String email, Role role) {
        if (accounts.findByEmail(email).isEmpty()) {
            accounts.save(new UserAccount(email, passwordEncoder.encode(PASSWORD), role));
        }
    }

    private static String mailpit(String path) throws Exception {
        URI uri = URI.create("http://" + RELAY.getHost() + ":" + RELAY.getMappedPort(8025) + path);
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(uri).build(),
                HttpResponse.BodyHandlers.ofString()).body();
    }
}
