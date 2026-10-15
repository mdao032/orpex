package com.orpex.core.mail;

/**
 * Échec d'envoi. {@link #kind()} dit sa nature (pour un message clair à l'utilisateur) ; {@link #isRetryable()}
 * indique si une nouvelle tentative a un sens (serveur injoignable ou refus temporaire) ou non. Le message de
 * l'exception est le détail technique, conservé pour l'administrateur.
 */
public class MailSendException extends Exception {

    private static final long serialVersionUID = 1L;

    public enum Kind {
        /** Serveur de messagerie injoignable (réseau, panne) : temporaire. */
        RELAY_UNREACHABLE(true),
        /** Refus temporaire du serveur (code 4xx) : temporaire. */
        RELAY_TEMPORARY(true),
        /** Adresse du destinataire refusée. */
        RECIPIENT_REFUSED(false),
        /** Message refusé par le serveur (code 5xx). */
        RELAY_REFUSED(false),
        /** Identifiants de l'application refusés par le serveur. */
        AUTH_REFUSED(false),
        /** Configuration de la messagerie inutilisable (clé, certificat, message impossible à construire). */
        CONFIGURATION(false);

        private final boolean retryable;

        Kind(boolean retryable) {
            this.retryable = retryable;
        }

        public boolean isRetryable() {
            return retryable;
        }
    }

    private final Kind kind;

    public MailSendException(String message, Kind kind, Throwable cause) {
        super(message, cause);
        this.kind = kind;
    }

    public Kind kind() {
        return kind;
    }

    public boolean isRetryable() {
        return kind.isRetryable();
    }
}
