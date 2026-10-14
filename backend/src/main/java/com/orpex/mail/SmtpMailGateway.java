package com.orpex.mail;

import com.orpex.core.mail.MailGateway;
import com.orpex.core.mail.MailMessage;
import com.orpex.core.mail.MailSendException;
import jakarta.mail.MessagingException;
import jakarta.mail.SendFailedException;
import jakarta.mail.internet.MimeMessage;
import java.io.UnsupportedEncodingException;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.MailException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

/**
 * Envoi par SMTP (relais de la banque ; Mailpit en développement), avec la connexion en vigueur : celle de l'écran
 * d'administration, sinon {@code spring.mail.*} ({@link MailSenderProvider}).
 *
 * <p>Classement des erreurs pour les nouvelles tentatives : code SMTP 4xx ou serveur injoignable → temporaire ;
 * code 5xx, adresse refusée ou authentification refusée → définitif.
 */
@Component
public class SmtpMailGateway implements MailGateway {

    /** Code de réponse SMTP au début du message du serveur : « 550 5.1.1 … », « 451-… ». */
    private static final Pattern SMTP_CODE = Pattern.compile("^\\s*([245]\\d\\d)[\\s-]");
    private static final int MAX_DETAIL = 300;

    private final Supplier<MailSenderProvider.Current> connections;

    @Autowired
    public SmtpMailGateway(MailSenderProvider provider) {
        this(provider::current);
    }

    SmtpMailGateway(Supplier<MailSenderProvider.Current> connections) {
        this.connections = connections;
    }

    @Override
    public void send(MailMessage message) throws MailSendException {
        MailSenderProvider.Current connection;
        try {
            connection = connections.get();
        } catch (RuntimeException e) {
            throw new MailSendException("configuration SMTP inutilisable : " + e.getMessage(), false, e);
        }
        MimeMessage mime = connection.sender().createMimeMessage();
        try {
            MimeMessageHelper helper = new MimeMessageHelper(mime, false, "UTF-8");
            helper.setFrom(message.from(), connection.fromName());
            helper.setTo(message.to());
            helper.setSubject(message.subject());
            helper.setText(message.body(), false);
        } catch (MessagingException | UnsupportedEncodingException e) {
            throw new MailSendException("message impossible à construire : " + e.getMessage(), false, e);
        }
        try {
            connection.sender().send(mime);
        } catch (MailException e) {
            throw classify(e);
        }
    }

    static MailSendException classify(MailException e) {
        if (e instanceof MailAuthenticationException) {
            return new MailSendException("authentification refusée par le serveur de messagerie", false, e);
        }
        Optional<String> reply = smtpReply(e);
        if (reply.isPresent()) {
            Matcher code = SMTP_CODE.matcher(reply.get());
            code.find();
            boolean temporary = Integer.parseInt(code.group(1)) < 500;
            return new MailSendException((temporary ? "refus temporaire du serveur : " : "refusé par le serveur : ")
                    + shorten(reply.get()), temporary, e);
        }
        if (hasInvalidAddress(e)) {
            return new MailSendException("adresse refusée par le serveur : " + detail(e), false, e);
        }
        return new MailSendException("serveur de messagerie injoignable : " + detail(e), true, e);
    }

    /**
     * Réponse SMTP du serveur (« 550 5.1.0 … ») trouvée dans les erreurs imbriquées : causes, messages en échec
     * signalés par Spring, et erreurs suivantes de Jakarta Mail.
     */
    private static Optional<String> smtpReply(Throwable e) {
        if (e == null) {
            return Optional.empty();
        }
        if (e.getMessage() != null && SMTP_CODE.matcher(e.getMessage()).find()) {
            return Optional.of(e.getMessage().strip());
        }
        if (e instanceof org.springframework.mail.MailSendException send) {
            for (Exception failed : send.getFailedMessages().values()) {
                Optional<String> nested = smtpReply(failed);
                if (nested.isPresent()) {
                    return nested;
                }
            }
        }
        if (e instanceof MessagingException messaging && messaging.getNextException() != e) {
            Optional<String> next = smtpReply(messaging.getNextException());
            if (next.isPresent()) {
                return next;
            }
        }
        return e.getCause() == e ? Optional.empty() : smtpReply(e.getCause());
    }

    private static boolean hasInvalidAddress(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof SendFailedException failed && failed.getInvalidAddresses() != null
                    && failed.getInvalidAddresses().length > 0) {
                return true;
            }
            if (t instanceof org.springframework.mail.MailSendException send
                    && send.getFailedMessages().values().stream().anyMatch(SmtpMailGateway::hasInvalidAddress)) {
                return true;
            }
        }
        return false;
    }

    /** Message le plus précis de la chaîne des causes, raccourci : il est conservé avec l'envoi. */
    private static String detail(Throwable e) {
        Throwable deepest = e;
        while (deepest.getCause() != null && deepest.getCause() != deepest) {
            deepest = deepest.getCause();
        }
        return shorten(Optional.ofNullable(deepest.getMessage()).orElse(deepest.getClass().getSimpleName()));
    }

    private static String shorten(String text) {
        String oneLine = text.strip().replaceAll("\\s+", " ");
        return oneLine.length() > MAX_DETAIL ? oneLine.substring(0, MAX_DETAIL) + "…" : oneLine;
    }
}
