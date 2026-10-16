package com.orpex.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.orpex.TestcontainersConfiguration;
import java.io.ByteArrayInputStream;
import java.math.BigInteger;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNames;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.openssl.jcajce.JcaPEMWriter;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Profil « tls » : ORPEX sert l'interface et l'API en HTTPS avec un certificat PEM, cookies réservés à HTTPS. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "server.port=0")
@ActiveProfiles("tls")
@Import(TestcontainersConfiguration.class)
class TlsIT {

    private static byte[] certificate;

    @LocalServerPort
    int port;

    @Autowired
    Environment environment;

    @DynamicPropertySource
    static void certificate(DynamicPropertyRegistry registry) throws Exception {
        KeyPair keys = KeyPairGenerator.getInstance("RSA").generateKeyPair();
        Instant now = Instant.now();
        X509Certificate cert = new org.bouncycastle.cert.jcajce.JcaX509CertificateConverter().getCertificate(
                new JcaX509v3CertificateBuilder(new X500Name("CN=localhost"), BigInteger.ONE,
                        Date.from(now.minus(1, ChronoUnit.DAYS)), Date.from(now.plus(1, ChronoUnit.DAYS)),
                        new X500Name("CN=localhost"), keys.getPublic())
                        .addExtension(Extension.subjectAlternativeName, false,
                                new GeneralNames(new GeneralName(GeneralName.dNSName, "localhost")))
                        .build(new JcaContentSignerBuilder("SHA256withRSA").build(keys.getPrivate())));
        certificate = cert.getEncoded();
        Path dir = Files.createTempDirectory("orpex-tls");
        Path certFile = dir.resolve("orpex.crt");
        Path keyFile = dir.resolve("orpex.key");
        try (JcaPEMWriter writer = new JcaPEMWriter(Files.newBufferedWriter(certFile))) {
            writer.writeObject(cert);
        }
        try (JcaPEMWriter writer = new JcaPEMWriter(Files.newBufferedWriter(keyFile))) {
            writer.writeObject(keys.getPrivate());
        }
        registry.add("ORPEX_TLS_CERTIFICATE", () -> "file:" + certFile);
        registry.add("ORPEX_TLS_PRIVATE_KEY", () -> "file:" + keyFile);
    }

    @Test
    void theApplicationAnswersInHttpsWithSecureCookiesAndHsts() throws Exception {
        HttpResponse<String> health = client().send(HttpRequest.newBuilder(uri("/actuator/health")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(health.statusCode()).isEqualTo(200);
        assertThat(health.body()).contains("\"UP\"");

        HttpResponse<String> me = client().send(HttpRequest.newBuilder(uri("/api/me")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(me.statusCode()).isEqualTo(401);
        assertThat(me.headers().firstValue("Strict-Transport-Security")).isPresent();
        assertThat(me.headers().allValues("Set-Cookie")).anySatisfy(cookie ->
                assertThat(cookie).startsWith("XSRF-TOKEN=").contains("Secure"));
        assertThat(environment.getProperty("server.servlet.session.cookie.secure", Boolean.class)).isTrue();
    }

    private URI uri(String path) {
        return URI.create("https://localhost:" + port + path);
    }

    private static HttpClient client() throws Exception {
        KeyStore trusted = KeyStore.getInstance(KeyStore.getDefaultType());
        trusted.load(null, null);
        trusted.setCertificateEntry("orpex", CertificateFactory.getInstance("X.509")
                .generateCertificate(new ByteArrayInputStream(certificate)));
        TrustManagerFactory trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trust.init(trusted);
        SSLContext ssl = SSLContext.getInstance("TLS");
        ssl.init(null, trust.getTrustManagers(), null);
        return HttpClient.newBuilder().sslContext(ssl).build();
    }
}
