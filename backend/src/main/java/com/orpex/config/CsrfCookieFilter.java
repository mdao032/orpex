package com.orpex.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Force l'écriture du cookie XSRF-TOKEN à chaque requête : Angular le relit et le renvoie dans
 * l'en-tête X-XSRF-TOKEN pour toute requête qui modifie des données.
 */
final class CsrfCookieFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        CsrfToken token = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
        if (token != null) {
            token.getToken(); // le simple fait de lire le jeton déclenche l'écriture du cookie
        }
        chain.doFilter(request, response);
    }
}
