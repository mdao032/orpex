package com.orpex.account;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/** Identifiant en base de l'utilisateur connecté : c'est lui qui filtre toutes les données (propriétaire). */
@Component
public class CurrentUser {

    private final UserAccountRepository accounts;

    public CurrentUser(UserAccountRepository accounts) {
        this.accounts = accounts;
    }

    public long id(Authentication authentication) {
        return accounts.findByEmail(UserAccount.normalizeEmail(authentication.getName()))
                .map(UserAccount::getId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));
    }
}
