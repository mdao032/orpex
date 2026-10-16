package com.orpex.account;

import com.orpex.audit.AuditEvent;
import com.orpex.audit.AuditLog;
import com.orpex.config.OrpexProperties;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.authentication.event.AbstractAuthenticationFailureEvent;
import org.springframework.security.authentication.event.AuthenticationFailureBadCredentialsEvent;
import org.springframework.security.authentication.event.AuthenticationSuccessEvent;
import org.springframework.security.core.AuthenticationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Compte les échecs de connexion et verrouille temporairement le compte (spécification §3).
 * Un email inconnu n'a pas de ligne en base : rien n'est compté. Une tentative sur un compte déjà verrouillé
 * n'est pas un échec de mot de passe et ne prolonge pas le verrouillage.
 */
@Component
class LoginAttemptListener {

    private static final Logger log = LoggerFactory.getLogger(LoginAttemptListener.class);

    private final UserAccountRepository accounts;
    private final OrpexProperties.Login settings;
    private final AuditLog audit;

    LoginAttemptListener(UserAccountRepository accounts, OrpexProperties properties, AuditLog audit) {
        this.accounts = accounts;
        this.settings = properties.login();
        this.audit = audit;
    }

    @EventListener
    @Transactional
    public void onBadCredentials(AuthenticationFailureBadCredentialsEvent event) {
        Instant now = Instant.now();
        accounts.findByEmail(UserAccount.normalizeEmail(event.getAuthentication().getName())).ifPresent(account -> {
            account.recordFailedLogin(settings.maxFailedAttempts(), settings.lockDuration(), now);
            if (account.isLockedAt(now)) {
                log.warn("Compte {} verrouillé jusqu'à {} après {} échecs de connexion",
                        account.getId(), account.getLockedUntil(), settings.maxFailedAttempts());
            }
        });
    }

    @EventListener
    @Transactional
    public void onSuccess(AuthenticationSuccessEvent event) {
        accounts.findByEmail(UserAccount.normalizeEmail(event.getAuthentication().getName()))
                .ifPresent(account -> {
                    account.recordSuccessfulLogin(Instant.now());
                    audit.recordAs(account.getId(), AuditEvent.LOGIN_SUCCEEDED, account.getId(), "USER",
                            account.getEmail(), null, null);
                });
    }

    /** Journal (spécification §11) : tout échec, mot de passe faux, compte inconnu, verrouillé ou désactivé. */
    @EventListener
    @Transactional
    public void onFailure(AbstractAuthenticationFailureEvent event) {
        String email = UserAccount.normalizeEmail(event.getAuthentication().getName());
        Long id = accounts.findByEmail(email).map(UserAccount::getId).orElse(null);
        audit.recordAs(id, AuditEvent.LOGIN_FAILED, id, "USER", email, null, reason(event.getException()));
    }

    private static String reason(AuthenticationException exception) {
        return switch (exception) {
            case LockedException e -> "compte verrouillé";
            case DisabledException e -> "compte désactivé";
            case BadCredentialsException e -> "identifiants incorrects";
            default -> exception.getClass().getSimpleName();
        };
    }
}
