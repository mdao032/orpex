package com.orpex.config;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Au démarrage hors développement : signale dans les journaux une configuration de production incomplète (sans
 * bloquer le démarrage, pour qu'un essai reste possible).
 */
@Component
class StartupChecks {

    private static final Logger log = LoggerFactory.getLogger(StartupChecks.class);

    private final Environment environment;

    StartupChecks(Environment environment) {
        this.environment = environment;
    }

    @EventListener(ApplicationReadyEvent.class)
    void check() {
        if (Arrays.asList(environment.getActiveProfiles()).contains("dev")) {
            return;
        }
        problems().forEach(problem -> log.warn("Configuration de production : {}", problem));
    }

    List<String> problems() {
        List<String> problems = new ArrayList<>();
        if (environment.getProperty("orpex.secret-key", "").isBlank()) {
            problems.add("ORPEX_SECRET_KEY absente : aucun mot de passe SMTP ne pourra être enregistré.");
        }
        String appUrl = environment.getProperty("orpex.app-url", "");
        if (!appUrl.startsWith("https://") && !appUrl.contains("://localhost")) {
            problems.add("ORPEX_APP_URL (" + appUrl + ") n'est pas en HTTPS : les liens des mails ne seront pas chiffrés.");
        }
        boolean https = environment.getProperty("server.ssl.enabled", Boolean.class, false)
                || !"none".equalsIgnoreCase(environment.getProperty("server.forward-headers-strategy", "none"));
        if (https && !environment.getProperty("server.servlet.session.cookie.secure", Boolean.class, false)) {
            problems.add("HTTPS actif mais cookie de session non réservé à HTTPS : définir ORPEX_COOKIE_SECURE=true.");
        }
        if (!https && appUrl.startsWith("https://")) {
            problems.add("ORPEX_APP_URL est en HTTPS mais ni le profil tls ni ORPEX_FORWARD_HEADERS ne sont réglés.");
        }
        return problems;
    }
}
