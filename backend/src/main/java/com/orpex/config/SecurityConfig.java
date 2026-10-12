package com.orpex.config;

import com.orpex.account.AccountStatusFilter;
import com.orpex.account.UserAccountRepository;
import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.logout.HttpStatusReturningLogoutSuccessHandler;
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;

/**
 * Sécurité de l'API : session par cookie, protection CSRF compatible Angular.
 *
 * <p>Le front Angular est servi par la même application ; seules les URL {@code /api/**} exigent une connexion.
 * La connexion se fait par un POST sur {@code /login} (formulaire) et répond 200 ou 401, sans redirection.
 * Comme toute requête qui modifie, elle exige l'en-tête {@code X-XSRF-TOKEN} recopié du cookie {@code XSRF-TOKEN}.
 */
@Configuration
@EnableWebSecurity
class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, UserAccountRepository accounts) throws Exception {
        http
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health/**").permitAll()
                        .requestMatchers("/api/admin/**").hasRole("ADMIN")
                        .requestMatchers("/api/**").authenticated()
                        .anyRequest().permitAll())
                .formLogin(form -> form
                        .successHandler((request, response, authentication) -> {
                            // La connexion remplace le jeton CSRF : on écrit tout de suite le nouveau dans le cookie,
                            // pour que la requête suivante du front n'ait pas besoin d'un GET préalable.
                            CsrfToken csrf = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
                            if (csrf != null) {
                                csrf.getToken();
                            }
                            response.setStatus(HttpStatus.OK.value());
                        })
                        .failureHandler((request, response, exception) ->
                                response.setStatus(HttpStatus.UNAUTHORIZED.value())))
                .logout(logout -> logout.logoutSuccessHandler(new HttpStatusReturningLogoutSuccessHandler()))
                .exceptionHandling(errors -> errors.defaultAuthenticationEntryPointFor(
                        new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED),
                        request -> request.getRequestURI().startsWith("/api/")))
                // CSRF sur toutes les requêtes qui modifient, POST /login compris : le front obtient le cookie XSRF-TOKEN
                // par n'importe quelle requête préalable (par exemple GET /api/me, même en 401).
                .csrf(csrf -> csrf
                        .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                        .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler()))
                .addFilterAfter(new CsrfCookieFilter(), BasicAuthenticationFilter.class)
                // État du compte relu en base : désactivation, rôle changé, mot de passe provisoire.
                .addFilterAfter(new AccountStatusFilter(accounts), AuthorizationFilter.class);
        return http.build();
    }

    /**
     * Nouveaux mots de passe hachés en Argon2id (spécification §3). Les hachages bcrypt déjà en base restent lisibles
     * et sont convertis en Argon2id à la connexion réussie suivante (voir {@code DatabaseUserDetailsService}).
     */
    @Bean
    PasswordEncoder passwordEncoder() {
        Map<String, PasswordEncoder> encoders = Map.of(
                "argon2", Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8(),
                "bcrypt", new BCryptPasswordEncoder());
        return new DelegatingPasswordEncoder("argon2", encoders);
    }
}
