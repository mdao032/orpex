package com.orpex.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Réglages propres à ORPEX (préfixe {@code orpex} dans application.properties).
 *
 * @param timezone                fuseau horaire unique de l'installation, utilisé pour les heures d'envoi
 * @param validationCutoffMinutes heure limite de validation, en minutes avant l'heure d'envoi
 * @param bootstrapAdmin          premier administrateur, créé au démarrage si la table {@code users} est vide
 * @param login                   limitation des tentatives de connexion
 */
@ConfigurationProperties(prefix = "orpex")
public record OrpexProperties(String timezone, int validationCutoffMinutes, BootstrapAdmin bootstrapAdmin,
        Login login) {

    public record BootstrapAdmin(String email, String password) {

        public boolean isConfigured() {
            return email != null && !email.isBlank() && password != null && !password.isBlank();
        }
    }

    /**
     * @param maxFailedAttempts échecs consécutifs qui déclenchent le verrouillage
     * @param lockDuration      durée du verrouillage temporaire
     */
    public record Login(int maxFailedAttempts, Duration lockDuration) {
    }
}
