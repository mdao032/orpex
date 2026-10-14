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
 * @param password                règles des mots de passe choisis par les utilisateurs
 * @param mail                    expéditeur des relances
 */
@ConfigurationProperties(prefix = "orpex")
public record OrpexProperties(String timezone, int validationCutoffMinutes, BootstrapAdmin bootstrapAdmin,
        Login login, Password password, Mail mail) {

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

    /**
     * @param minLength longueur minimale (recommandation ANSSI/OWASP : 12), sans autre règle de complexité
     * @param maxLength longueur maximale, pour borner le coût du hachage
     */
    public record Password(int minLength, int maxLength) {
    }

    /**
     * @param from     adresse expéditrice, idéalement une boîte de service (spécification §10)
     * @param fromName nom affiché de l'expéditeur
     */
    public record Mail(String from, String fromName) {
    }
}
