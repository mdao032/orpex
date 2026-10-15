package com.orpex.sending;

import com.orpex.core.mail.MailGateway;
import com.orpex.core.mail.MailMessage;
import com.orpex.core.mail.MailSendException;
import com.orpex.core.mail.MessageTemplate;
import com.orpex.mail.MailSenderProvider;
import com.orpex.rule.Rule;
import com.orpex.rule.RuleRepository;
import com.orpex.rule.SendIssue;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * « Envoi de test vers moi-même » (spécification §10) : le rendu exact d'une relance, variables remplacées, envoyé à
 * l'utilisateur connecté. Aucun envoi planifié n'est créé ni modifié.
 */
@Service
public class TestSendService {

    /** {@code issue} : nature de l'échec (message clair à l'écran) ; {@code detail} : erreur technique du relais. */
    public record Result(boolean success, String to, SendIssue issue, String detail) {
    }

    private final RuleRepository rules;
    private final MailGateway gateway;
    private final MailSenderProvider senders;

    public TestSendService(RuleRepository rules, MailGateway gateway, MailSenderProvider senders) {
        this.rules = rules;
        this.gateway = gateway;
        this.senders = senders;
    }

    @Transactional(readOnly = true)
    public Result sendToMyself(long ownerId, long ruleId, String myEmail) {
        Rule rule = rules.findByIdAndOwnerId(ruleId, ownerId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Relance introuvable."));
        Map<String, String> values = rule.variableValues();
        String subject;
        String body;
        try {
            subject = MessageTemplate.render(rule.getSubject(), values);
            body = MessageTemplate.render(rule.getBody(), values);
        } catch (MessageTemplate.MissingVariableException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Message incomplet : " + e.getMessage() + ".");
        }
        String intro = "[Envoi de test : ce message serait envoyé à " + rule.getRecipientEmail() + "]\n\n";
        try {
            gateway.send(new MailMessage(senders.current().from(), myEmail, "[Test] " + subject, intro + body));
            return new Result(true, myEmail, null, null);
        } catch (MailSendException e) {
            return new Result(false, myEmail, SendIssue.of(e.kind()), e.getMessage());
        }
    }
}
