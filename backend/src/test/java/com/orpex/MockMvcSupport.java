package com.orpex;

import static com.orpex.CsrfSupport.realCsrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** Requêtes MockMvc telles que le front les envoie : connexion par formulaire, JSON avec jeton CSRF. */
public final class MockMvcSupport {

    private MockMvcSupport() {
    }

    public static ResultActions attemptLogin(MockMvc mvc, String email, String password) throws Exception {
        return mvc.perform(post("/login").param("username", email).param("password", password).with(realCsrf(mvc)));
    }

    /** Session d'un utilisateur connecté, à passer aux requêtes suivantes avec {@code .session(...)}. */
    public static MockHttpSession login(MockMvc mvc, String email, String password) throws Exception {
        return (MockHttpSession) attemptLogin(mvc, email, password)
                .andExpect(status().isOk())
                .andReturn().getRequest().getSession(false);
    }

    /** Requête qui modifie (POST, PUT, DELETE), avec jeton CSRF et corps JSON (éventuellement vide). */
    public static ResultActions send(MockMvc mvc, MockHttpServletRequestBuilder request, String json) throws Exception {
        return mvc.perform(request.with(realCsrf(mvc)).contentType(MediaType.APPLICATION_JSON).content(json));
    }
}
