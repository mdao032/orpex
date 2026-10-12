package com.orpex.account;

import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsPasswordService;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Connexion par les comptes de la table {@code users} ; un compte désactivé ne peut pas se connecter.
 * Après une connexion réussie avec un hachage ancien (bcrypt), Spring Security appelle {@link #updatePassword}
 * pour le remplacer par un hachage Argon2id.
 */
@Service
public class DatabaseUserDetailsService implements UserDetailsService, UserDetailsPasswordService {

    private final UserAccountRepository accounts;

    public DatabaseUserDetailsService(UserAccountRepository accounts) {
        this.accounts = accounts;
    }

    @Override
    @Transactional(readOnly = true)
    public UserDetails loadUserByUsername(String username) {
        UserAccount account = accounts.findByEmail(UserAccount.normalizeEmail(username))
                .orElseThrow(() -> new UsernameNotFoundException("Compte inconnu"));
        return User.withUsername(account.getEmail())
                .password(account.getPasswordHash())
                .roles(account.getRole().name())
                .disabled(!account.isEnabled())
                .build();
    }

    @Override
    @Transactional
    public UserDetails updatePassword(UserDetails user, String newPasswordHash) {
        UserAccount account = accounts.findByEmail(UserAccount.normalizeEmail(user.getUsername()))
                .orElseThrow(() -> new UsernameNotFoundException("Compte inconnu"));
        account.changePasswordHash(newPasswordHash);
        return User.withUserDetails(user).password(newPasswordHash).build();
    }
}
