package com.orpex.core.mail;

/**
 * Échec d'envoi. {@code retryable} indique si une nouvelle tentative a un sens
 * (erreur réseau ou serveur temporairement indisponible) ou non (adresse refusée).
 */
public class MailSendException extends Exception {

    private static final long serialVersionUID = 1L;

    private final boolean retryable;

    public MailSendException(String message, boolean retryable, Throwable cause) {
        super(message, cause);
        this.retryable = retryable;
    }

    public boolean isRetryable() {
        return retryable;
    }
}
