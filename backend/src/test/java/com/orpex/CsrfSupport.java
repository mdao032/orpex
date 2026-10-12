package com.orpex;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import jakarta.servlet.http.Cookie;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Jeton CSRF obtenu comme le fait le front Angular : cookie XSRF-TOKEN reçu, recopié dans l'en-tête X-XSRF-TOKEN.
 *
 * <p>Ne pas utiliser {@code SecurityMockMvcRequestPostProcessors.csrf()} : il remplace le dépôt de jetons pour tout
 * le contexte Spring partagé, et les tests suivants ne reçoivent plus de cookie XSRF-TOKEN.
 */
public final class CsrfSupport {

    public static final String COOKIE = "XSRF-TOKEN";
    public static final String HEADER = "X-XSRF-TOKEN";

    private CsrfSupport() {
    }

    /** Fait une requête préalable pour recevoir un jeton, puis l'ajoute (cookie et en-tête) à la requête modifiée. */
    public static RequestPostProcessor realCsrf(MockMvc mvc) throws Exception {
        Cookie cookie = xsrfCookie(mvc.perform(get("/api/me")).andReturn().getResponse());
        return request -> {
            request.setCookies(cookie);
            request.addHeader(HEADER, cookie.getValue());
            return request;
        };
    }

    /**
     * Dernier cookie XSRF-TOKEN de la réponse, comme le retient le navigateur : une réponse peut d'abord effacer
     * l'ancien jeton puis écrire le nouveau.
     */
    public static Cookie xsrfCookie(MockHttpServletResponse response) {
        Cookie last = null;
        for (Cookie cookie : response.getCookies()) {
            if (COOKIE.equals(cookie.getName())) {
                last = cookie;
            }
        }
        return last;
    }
}
