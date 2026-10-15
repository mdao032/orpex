package com.orpex.audit;

import com.orpex.account.UserAccount;
import com.orpex.account.UserAccountRepository;
import java.time.Clock;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Écrit le journal d'audit (spécification §11), dans la transaction de l'action : une action annulée ne laisse pas
 * de trace, une action faite en laisse toujours une. L'auteur est l'utilisateur connecté, ou le système (nul).
 * Jamais de mot de passe dans le journal.
 */
@Service
public class AuditLog {

    private final AuditRepository entries;
    private final UserAccountRepository accounts;
    private final Clock clock;

    public AuditLog(AuditRepository entries, UserAccountRepository accounts, Clock clock) {
        this.entries = entries;
        this.accounts = accounts;
        this.clock = clock;
    }

    /** Action de l'utilisateur connecté (ou du système hors requête) sur un objet appartenant à {@code ownerId}. */
    @Transactional
    public void record(AuditEvent event, Long ownerId, String objectType, Object objectId, String oldValue,
            String newValue) {
        recordAs(currentActor(), event, ownerId, objectType, objectId, oldValue, newValue);
    }

    /** Événement sans objet (ex. pause générale). */
    @Transactional
    public void record(AuditEvent event, String newValue) {
        record(event, null, null, null, null, newValue);
    }

    /** Auteur explicite : connexion, où l'utilisateur n'est pas encore dans le contexte de sécurité. */
    @Transactional
    public void recordAs(Long actorId, AuditEvent event, Long ownerId, String objectType, Object objectId,
            String oldValue, String newValue) {
        entries.save(new AuditEntry(clock.instant(), actorId, event, objectType,
                objectId == null ? null : objectId.toString(), oldValue, newValue, ownerId));
    }

    private Long currentActor() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken) {
            return null;
        }
        return accounts.findByEmail(UserAccount.normalizeEmail(authentication.getName()))
                .map(UserAccount::getId).orElse(null);
    }
}
