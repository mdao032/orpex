package com.orpex.config;

import static com.orpex.CsrfSupport.HEADER;
import static com.orpex.CsrfSupport.xsrfCookie;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.orpex.TestcontainersConfiguration;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

/** Reproduit le dialogue du front Angular : cookie XSRF-TOKEN reçu, puis recopié dans l'en-tête X-XSRF-TOKEN. */
@SpringBootTest(properties = {
        "orpex.bootstrap-admin.email= Admin@Test.Local ",
        "orpex.bootstrap-admin.password=secret-de-test"
})
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class LoginCsrfIT {

    @Autowired
    MockMvc mvc;

    @Test
    void loginWithoutCsrfTokenIsForbidden() throws Exception {
        mvc.perform(post("/login").param("username", "admin@test.local").param("password", "secret-de-test"))
                .andExpect(status().isForbidden());
    }

    @Test
    void loginWithTheTokenFromAPriorRequestSucceedsAndReceivesAFreshToken() throws Exception {
        Cookie before = xsrfCookie(mvc.perform(get("/api/me"))
                .andExpect(status().isUnauthorized())
                .andReturn().getResponse());
        assertThat(before).isNotNull();
        assertThat(before.getValue()).isNotBlank();

        Cookie after = xsrfCookie(mvc.perform(post("/login")
                        .param("username", "admin@test.local").param("password", "secret-de-test")
                        .cookie(before).header(HEADER, before.getValue()))
                .andExpect(status().isOk())
                .andReturn().getResponse());

        assertThat(after).isNotNull();
        assertThat(after.getValue()).isNotBlank().isNotEqualTo(before.getValue());
    }

    @Test
    void tokenThatDoesNotMatchTheCookieIsForbidden() throws Exception {
        Cookie cookie = xsrfCookie(mvc.perform(get("/api/me")).andReturn().getResponse());

        mvc.perform(post("/login")
                        .param("username", "admin@test.local").param("password", "secret-de-test")
                        .cookie(cookie).header(HEADER, "jeton-invente"))
                .andExpect(status().isForbidden());
    }

    @Test
    void wrongPasswordWithValidTokenIsUnauthorized() throws Exception {
        Cookie cookie = xsrfCookie(mvc.perform(get("/api/me")).andReturn().getResponse());

        mvc.perform(post("/login")
                        .param("username", "admin@test.local").param("password", "faux")
                        .cookie(cookie).header(HEADER, cookie.getValue()))
                .andExpect(status().isUnauthorized());
    }
}
