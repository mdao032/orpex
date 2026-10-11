package com.orpex.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Réglages propres à ORPEX (préfixe {@code orpex} dans application.properties).
 *
 * @param timezone                fuseau horaire unique de l'installation, utilisé pour les heures d'envoi
 * @param validationCutoffMinutes heure limite de validation, en minutes avant l'heure d'envoi
 * @param bootstrapAdmin          compte administrateur de démarrage (temporaire, avant la gestion des comptes en base)
 */
@ConfigurationProperties(prefix = "orpex")
public record OrpexProperties(String timezone, int validationCutoffMinutes, BootstrapAdmin bootstrapAdmin) {

    public record BootstrapAdmin(String email, String password) {

        public boolean isConfigured() {
            return email != null && !email.isBlank() && password != null && !password.isBlank();
        }
    }
}
