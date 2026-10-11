package com.orpex.core.mail;

/**
 * Point d'entrée unique pour expédier un mail. L'implémentation SMTP (relais interne de la banque)
 * viendra dans un second temps ; d'autres fournisseurs pourront s'y brancher sans toucher au reste.
 */
public interface MailGateway {

    /** Remet le mail au serveur d'envoi et ne rend la main qu'une fois qu'il l'a accepté. */
    void send(MailMessage message) throws MailSendException;
}
