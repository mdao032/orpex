package com.orpex.account;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserAccountRepository extends JpaRepository<UserAccount, Long> {

    /** {@code email} doit déjà être normalisé ({@link UserAccount#normalizeEmail}). */
    Optional<UserAccount> findByEmail(String email);
}
