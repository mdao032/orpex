package com.orpex.account;

import com.orpex.config.OrpexProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Premier démarrage : si aucun compte n'existe, crée l'administrateur décrit par {@code orpex.bootstrap-admin.*}.
 * Son mot de passe est provisoire : il devra le changer à la première connexion. Dès qu'un compte existe, cette
 * configuration est ignorée (elle ne réinitialise jamais un mot de passe).
 */
@Component
class BootstrapAdminInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(BootstrapAdminInitializer.class);

    private final UserAccountRepository accounts;
    private final PasswordEncoder passwordEncoder;
    private final OrpexProperties properties;

    BootstrapAdminInitializer(UserAccountRepository accounts, PasswordEncoder passwordEncoder,
            OrpexProperties properties) {
        this.accounts = accounts;
        this.passwordEncoder = passwordEncoder;
        this.properties = properties;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (accounts.count() > 0) {
            return;
        }
        OrpexProperties.BootstrapAdmin admin = properties.bootstrapAdmin();
        if (admin == null || !admin.isConfigured()) {
            log.warn("Aucun compte en base et orpex.bootstrap-admin non configuré : personne ne peut se connecter. "
                    + "Définir ORPEX_ADMIN_EMAIL et ORPEX_ADMIN_PASSWORD puis redémarrer.");
            return;
        }
        UserAccount created = accounts.save(
                UserAccount.withTemporaryPassword(admin.email(), passwordEncoder.encode(admin.password()), Role.ADMIN));
        log.info("Premier administrateur créé (compte {}), mot de passe provisoire à changer", created.getId());
    }
}
