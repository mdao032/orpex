package com.orpex.audit;

/** Événements du journal d'audit (spécification §11), avec leur libellé pour l'écran et l'export. */
public enum AuditEvent {
    LOGIN_SUCCEEDED("Connexion"),
    LOGIN_FAILED("Connexion refusée"),
    ACCOUNT_CREATED("Compte créé"),
    ACCOUNT_DISABLED("Compte désactivé"),
    ACCOUNT_ENABLED("Compte réactivé"),
    ACCOUNT_ROLE_CHANGED("Rôle modifié"),
    ACCOUNT_PASSWORD_RESET("Mot de passe réinitialisé"),
    PASSWORD_CHANGED("Mot de passe changé"),
    IMPORT_APPLIED("Import"),
    RULE_UPDATED("Relance modifiée"),
    RULE_ACTIVATED("Relance activée"),
    RULE_PAUSED("Relance mise en pause"),
    RULE_RESUMED("Relance reprise"),
    RULE_CLOSED("Dossier clos"),
    RULE_RESCHEDULED("Envois recalculés"),
    VALIDATION_DROPPED("Validation quotidienne retirée"),
    SEND_DECISION_REOPENED("Décision de validation reprise"),
    /** Écrit par la base (trigger sur {@code occurrences}, V12) à chaque changement d'état d'un envoi. */
    SEND_STATE_CHANGED("Envoi"),
    SEND_RESOLVED("Envoi à vérifier tranché"),
    TEST_SEND("Envoi de test"),
    VALIDATION_REMINDER_SENT("Récapitulatif de validation envoyé"),
    SMTP_SETTINGS_CHANGED("Messagerie modifiée"),
    SMTP_TEST_SENT("Mail de test de la messagerie"),
    SENDING_PAUSED("Pause générale des envois"),
    SENDING_RESUMED("Reprise générale des envois"),
    HOLIDAY_ADDED("Jour férié ajouté"),
    HOLIDAY_REMOVED("Jour férié retiré");

    private final String label;

    AuditEvent(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
