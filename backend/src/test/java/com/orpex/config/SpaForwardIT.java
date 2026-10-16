package com.orpex.config;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.orpex.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

/** Une page de l'interface rechargée dans le navigateur renvoie l'application Angular, sans connexion préalable. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class SpaForwardIT {

    @Autowired
    MockMvc mvc;

    @Test
    void angularPagesAreServedByIndexHtml() throws Exception {
        for (String page : new String[] {"/lignes", "/lignes/12", "/journal", "/compte", "/admin/envois", "/connexion"}) {
            mvc.perform(get(page)).andExpect(status().isOk()).andExpect(forwardedUrl("/index.html"));
        }
    }

    @Test
    void theApiIsNotForwarded() throws Exception {
        mvc.perform(get("/api/inconnu")).andExpect(status().isUnauthorized());
    }
}
