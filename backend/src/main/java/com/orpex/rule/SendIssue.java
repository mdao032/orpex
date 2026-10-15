package com.orpex.rule;

import com.orpex.core.mail.MailSendException;

/**
 * Nature du dernier problème (ou de la décision) d'un envoi, pour que l'écran affiche une phrase claire ; le détail
 * technique reste dans {@code last_error}.
 */
public enum SendIssue {
    RELAY_UNREACHABLE,
    RELAY_TEMPORARY,
    RECIPIENT_REFUSED,
    RELAY_REFUSED,
    AUTH_REFUSED,
    CONFIGURATION,
    /** Une variable du message n'a pas de valeur. */
    MESSAGE_INCOMPLETE,
    /** Arrêt de l'application pendant l'envoi : à vérifier. */
    INTERRUPTED,
    /** Envoi « à vérifier » déclaré parti par l'utilisateur. */
    CONFIRMED_SENT,
    /** Envoi « à vérifier » déclaré non parti par l'utilisateur. */
    CONFIRMED_NOT_SENT;

    public static SendIssue of(MailSendException.Kind kind) {
        return valueOf(kind.name());
    }
}
