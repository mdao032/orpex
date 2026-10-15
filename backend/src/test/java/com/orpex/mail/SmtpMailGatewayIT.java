package com.orpex.mail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.orpex.core.mail.MailMessage;
import com.orpex.core.mail.MailSendException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Envoi SMTP réel vers Mailpit (même version que pour le développement), relu par son API. */
class SmtpMailGatewayIT {

    /** Mailpit refuse tout destinataire hors de ce domaine (code 5xx), pour tester une adresse refusée. */
    private static final GenericContainer<?> MAILPIT = new GenericContainer<>("axllent/mailpit:v1.31.5")
            .withExposedPorts(1025, 8025)
            .withEnv("MP_SMTP_ALLOWED_RECIPIENTS", "@exemple\\.ml$")
            .withEnv("MP_SMTP_DISABLE_RDNS", "true")
            .waitingFor(Wait.forHttp("/livez").forPort(8025));


    @BeforeAll
    static void start() {
        MAILPIT.start();
    }

    @AfterAll
    static void stop() {
        MAILPIT.stop();
    }

    @Test
    void sendsAPlainTextUtf8MailThatTheServerAccepts() throws Exception {
        gateway(MAILPIT.getMappedPort(1025)).send(new MailMessage("relances@banque.ml", "awa.traore@exemple.ml",
                "Relance : facture n° FAC-1 échue", "Bonjour Awa,\nla facture FAC-1 (125 000 FCFA) reste à régler."));

        JsonNode message = mailpit("/api/v1/messages").get("messages").get(0);
        assertThat(message.get("Subject").asString()).isEqualTo("Relance : facture n° FAC-1 échue");
        assertThat(message.get("To").get(0).get("Address").asString()).isEqualTo("awa.traore@exemple.ml");
        assertThat(message.get("From").get("Name").asString()).isEqualTo("ORPEX - Relances");
        assertThat(message.get("From").get("Address").asString()).isEqualTo("relances@banque.ml");
        String body = mailpit("/api/v1/message/" + message.get("ID").asString()).get("Text").asString();
        assertThat(body).contains("Bonjour Awa,").contains("125 000 FCFA");
    }

    @Test
    void aRecipientRefusedByTheServerIsAFinalFailure() {
        assertThatThrownBy(() -> gateway(MAILPIT.getMappedPort(1025)).send(
                new MailMessage("relances@banque.ml", "inconnu@ailleurs.com", "Relance", "Bonjour")))
                .isInstanceOfSatisfying(MailSendException.class, e -> {
                    assertThat(e.isRetryable()).as("ne pas réessayer : %s", e.getMessage()).isFalse();
                    assertThat(e.kind()).isEqualTo(MailSendException.Kind.RECIPIENT_REFUSED);
                    assertThat(e.getMessage()).startsWith("refusé par le serveur : 5");
                });
    }

    @Test
    void anUnreachableServerIsATemporaryFailure() {
        assertThatThrownBy(() -> gateway(1).send(
                new MailMessage("relances@banque.ml", "awa.traore@exemple.ml", "Relance", "Bonjour")))
                .isInstanceOfSatisfying(MailSendException.class, e -> {
                    assertThat(e.isRetryable()).as("réessayer : %s", e.getMessage()).isTrue();
                    assertThat(e.kind()).isEqualTo(MailSendException.Kind.RELAY_UNREACHABLE);
                    assertThat(e.getMessage()).startsWith("serveur de messagerie injoignable");
                });
    }

    private static SmtpMailGateway gateway(int port) {
        JavaMailSenderImpl sender = new JavaMailSenderImpl();
        sender.setHost(MAILPIT.getHost());
        sender.setPort(port);
        sender.getJavaMailProperties().put("mail.smtp.connectiontimeout", "3000");
        sender.getJavaMailProperties().put("mail.smtp.timeout", "3000");
        sender.getJavaMailProperties().put("mail.smtp.localhost", "orpex.local");
        return new SmtpMailGateway(() -> new MailSenderProvider.Current(sender, "relances@banque.ml", "ORPEX - Relances"));
    }

    private static JsonNode mailpit(String path) throws Exception {
        URI uri = URI.create("http://" + MAILPIT.getHost() + ":" + MAILPIT.getMappedPort(8025) + path);
        String json = HttpClient.newHttpClient()
                .send(HttpRequest.newBuilder(uri).build(), HttpResponse.BodyHandlers.ofString()).body();
        return JsonMapper.builder().build().readTree(json);
    }
}
