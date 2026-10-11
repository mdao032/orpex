package com.orpex.core.mail;

/** Un mail prêt à partir : le contenu a déjà été rédigé par l'utilisateur et ses variables remplacées. */
public record MailMessage(String from, String to, String subject, String body) {
}
