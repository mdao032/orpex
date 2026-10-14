package com.orpex.config;

import com.orpex.crypto.SecretCipher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Chiffrement des secrets au repos : une clé mal formée empêche le démarrage. */
@Configuration
class CryptoConfig {

    @Bean
    SecretCipher secretCipher(OrpexProperties properties) {
        return new SecretCipher(properties.secretKey());
    }
}
