package com.orpex.account;

import com.orpex.audit.AuditEvent;
import com.orpex.audit.AuditLog;
import com.orpex.config.OrpexProperties;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Gestion des comptes (spécification §3). Un administrateur ne peut ni se désactiver ni se retirer le rôle
 * administrateur : celui qui agit reste administrateur actif, l'application n'en manque donc jamais.
 * Les messages des exceptions sont affichés tels quels à l'utilisateur.
 */
@Service
public class AccountService {

    private final UserAccountRepository accounts;
    private final PasswordEncoder passwordEncoder;
    private final OrpexProperties.Password passwordRules;
    private final AuditLog audit;

    public AccountService(UserAccountRepository accounts, PasswordEncoder passwordEncoder,
            OrpexProperties properties, AuditLog audit) {
        this.accounts = accounts;
        this.passwordEncoder = passwordEncoder;
        this.passwordRules = properties.password();
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public List<UserAccount> list() {
        return accounts.findAllByOrderByEmailAsc();
    }

    @Transactional
    public UserAccount create(String email, Role role, String temporaryPassword) {
        String normalized = UserAccount.normalizeEmail(email);
        if (accounts.existsByEmail(normalized)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Un compte existe déjà avec l'adresse " + normalized + ".");
        }
        checkRules(temporaryPassword);
        UserAccount created = accounts.save(
                UserAccount.withTemporaryPassword(normalized, passwordEncoder.encode(temporaryPassword), role));
        audit.record(AuditEvent.ACCOUNT_CREATED, null, "USER", normalized, null, role.name());
        return created;
    }

    @Transactional
    public UserAccount disable(String actorEmail, long id) {
        UserAccount account = find(id);
        if (isActor(account, actorEmail)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Vous ne pouvez pas désactiver votre propre compte.");
        }
        account.disable();
        audit.record(AuditEvent.ACCOUNT_DISABLED, null, "USER", account.getEmail(), null, null);
        return account;
    }

    @Transactional
    public UserAccount enable(long id) {
        UserAccount account = find(id);
        account.enable();
        audit.record(AuditEvent.ACCOUNT_ENABLED, null, "USER", account.getEmail(), null, null);
        return account;
    }

    @Transactional
    public UserAccount changeRole(String actorEmail, long id, Role role) {
        UserAccount account = find(id);
        if (isActor(account, actorEmail) && role != Role.ADMIN) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Vous ne pouvez pas retirer votre propre rôle d'administrateur.");
        }
        Role before = account.getRole();
        account.changeRole(role);
        audit.record(AuditEvent.ACCOUNT_ROLE_CHANGED, null, "USER", account.getEmail(), before.name(), role.name());
        return account;
    }

    @Transactional
    public UserAccount resetPassword(long id, String temporaryPassword) {
        checkRules(temporaryPassword);
        UserAccount account = find(id);
        account.resetPassword(passwordEncoder.encode(temporaryPassword));
        audit.record(AuditEvent.ACCOUNT_PASSWORD_RESET, null, "USER", account.getEmail(), null,
                "mot de passe provisoire");
        return account;
    }

    /** L'utilisateur change son propre mot de passe : l'actuel est exigé, le nouveau doit être différent. */
    @Transactional
    public void changeOwnPassword(String email, String currentPassword, String newPassword) {
        UserAccount account = accounts.findByEmail(UserAccount.normalizeEmail(email))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));
        if (currentPassword == null || !passwordEncoder.matches(currentPassword, account.getPasswordHash())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Le mot de passe actuel est incorrect.");
        }
        if (newPassword.equals(currentPassword)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Le nouveau mot de passe doit être différent de l'actuel.");
        }
        checkRules(newPassword);
        account.changePassword(passwordEncoder.encode(newPassword));
        audit.recordAs(account.getId(), AuditEvent.PASSWORD_CHANGED, account.getId(), "USER", account.getEmail(),
                null, null);
    }

    private void checkRules(String password) {
        int length = password == null ? 0 : password.codePointCount(0, password.length());
        if (length < passwordRules.minLength()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Le mot de passe doit contenir au moins " + passwordRules.minLength() + " caractères.");
        }
        if (length > passwordRules.maxLength()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Le mot de passe ne doit pas dépasser " + passwordRules.maxLength() + " caractères.");
        }
    }

    private UserAccount find(long id) {
        return accounts.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Compte introuvable."));
    }

    private static boolean isActor(UserAccount account, String actorEmail) {
        return account.getEmail().equals(UserAccount.normalizeEmail(actorEmail));
    }
}
