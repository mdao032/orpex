package com.orpex.account;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.util.Optional;
import java.util.Set;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Relit l'état du compte en base à chaque appel de l'API, pour que les décisions de l'administrateur s'appliquent
 * sans attendre la fin de la session :
 * <ul>
 *   <li>compte désactivé (ou disparu), ou rôle changé depuis la connexion : session fermée, 401 ;</li>
 *   <li>mot de passe provisoire : seuls {@code /api/me} et le changement de mot de passe sont permis, sinon 403
 *       avec le code {@code PASSWORD_CHANGE_REQUIRED}.</li>
 * </ul>
 * Créé par la configuration de sécurité (pas un bean, pour ne pas être enregistré une seconde fois comme filtre).
 */
public final class AccountStatusFilter extends OncePerRequestFilter {

    static final String PASSWORD_CHANGE_REQUIRED = "PASSWORD_CHANGE_REQUIRED";
    private static final Set<String> ALLOWED_WITH_TEMPORARY_PASSWORD = Set.of("/api/me", "/api/me/password");

    private final UserAccountRepository accounts;

    public AccountStatusFilter(UserAccountRepository accounts) {
        this.accounts = accounts;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || authentication instanceof AnonymousAuthenticationToken) {
            chain.doFilter(request, response);
            return;
        }
        Optional<UserAccount> account = accounts.findByEmail(UserAccount.normalizeEmail(authentication.getName()));
        if (account.isEmpty() || !account.get().isEnabled() || roleChanged(authentication, account.get())) {
            HttpSession session = request.getSession(false);
            if (session != null) {
                session.invalidate();
            }
            SecurityContextHolder.clearContext();
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            return;
        }
        if (account.get().isMustChangePassword()
                && !ALLOWED_WITH_TEMPORARY_PASSWORD.contains(request.getRequestURI())) {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
            response.setCharacterEncoding("UTF-8");
            response.getWriter().write("{\"status\":403,\"code\":\"" + PASSWORD_CHANGE_REQUIRED + "\","
                    + "\"detail\":\"Vous devez d'abord remplacer votre mot de passe provisoire.\"}");
            return;
        }
        chain.doFilter(request, response);
    }

    /** Les droits de la session sont figés à la connexion : un changement de rôle impose de se reconnecter. */
    private static boolean roleChanged(Authentication authentication, UserAccount account) {
        String expected = "ROLE_" + account.getRole().name();
        return authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .filter(authority -> authority.startsWith("ROLE_"))
                .anyMatch(authority -> !authority.equals(expected));
    }
}
