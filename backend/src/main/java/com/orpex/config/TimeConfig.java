package com.orpex.config;

import java.time.Clock;
import java.time.DateTimeException;
import java.time.ZoneId;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Horloge unique de l'application, dans le fuseau de l'installation ({@code orpex.timezone}) : « aujourd'hui » et
 * les heures d'envoi se calculent avec elle, jamais avec le fuseau du serveur.
 */
@Configuration
class TimeConfig {

    @Bean
    Clock clock(OrpexProperties properties) {
        return Clock.system(zoneOf(properties.timezone()));
    }

    /** Un fuseau invalide empêche le démarrage : mieux vaut ne pas démarrer que d'envoyer à la mauvaise heure. */
    static ZoneId zoneOf(String timezone) {
        try {
            return ZoneId.of(timezone == null ? "" : timezone.strip());
        } catch (DateTimeException e) {
            throw new IllegalStateException("orpex.timezone (ORPEX_TIMEZONE) invalide : '" + timezone
                    + "'. Attendu : un fuseau IANA, par exemple Africa/Bamako ou Europe/Paris.", e);
        }
    }
}
