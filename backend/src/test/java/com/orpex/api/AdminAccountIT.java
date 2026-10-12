package com.orpex.api;

import static com.orpex.CsrfSupport.realCsrf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.orpex.TestcontainersConfiguration;
import com.orpex.account.Role;
import com.orpex.account.UserAccount;
import com.orpex.account.UserAccountRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@SpringBootTest(properties = {
        "orpex.bootstrap-admin.email= Admin@Test.Local ",
        "orpex.bootstrap-admin.password=secret-de-test"
})
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class AdminAccountIT {

    private static final String ADMIN = "chef@test.local";
    private static final String PASSWORD = "mot-de-passe-solide";
    private static final String TEMPORARY = "provisoire-123456";

    @Autowired
    MockMvc mvc;

    @Autowired
    UserAccountRepository accounts;

    @Autowired
    PasswordEncoder passwordEncoder;

    private MockHttpSession admin;

    @BeforeEach
    void loginAsAdmin() throws Exception {
        if (accounts.findByEmail(ADMIN).isEmpty()) {
            accounts.save(new UserAccount(ADMIN, passwordEncoder.encode(PASSWORD), Role.ADMIN));
        }
        admin = login(ADMIN, PASSWORD);
    }

    @Test
    void onlyAdministratorsCanManageAccounts() throws Exception {
        user("simple@test.local");
        MockHttpSession user = login("simple@test.local", PASSWORD);

        mvc.perform(get("/api/admin/accounts").session(user)).andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/accounts").session(admin)).andExpect(status().isOk());
    }

    @Test
    void newAccountMustReplaceItsTemporaryPasswordBeforeUsingTheApi() throws Exception {
        perform(post("/api/admin/accounts").session(admin),
                "{\"email\":\" Nouveau@Test.Local \",\"role\":\"USER\",\"temporaryPassword\":\"" + TEMPORARY + "\"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.email").value("nouveau@test.local"))
                .andExpect(jsonPath("$.mustChangePassword").value(true));

        MockHttpSession user = login("nouveau@test.local", TEMPORARY);
        mvc.perform(get("/api/me").session(user)).andExpect(jsonPath("$.mustChangePassword").value(true));
        mvc.perform(get("/api/rules").session(user))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PASSWORD_CHANGE_REQUIRED"));

        changeOwnPassword(user, "faux", "nouveau-mot-de-passe")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Le mot de passe actuel est incorrect."));
        changeOwnPassword(user, TEMPORARY, "court")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail", containsString("au moins 12 caractères")));
        changeOwnPassword(user, TEMPORARY, "nouveau-mot-de-passe").andExpect(status().isNoContent());

        mvc.perform(get("/api/me").session(user)).andExpect(jsonPath("$.mustChangePassword").value(false));
        mvc.perform(get("/api/rules").session(user)).andExpect(status().isNotFound());
    }

    @Test
    void creationIsRefusedForADuplicateEmailOrAShortPassword() throws Exception {
        user("double@test.local");

        perform(post("/api/admin/accounts").session(admin),
                "{\"email\":\"DOUBLE@test.local\",\"role\":\"USER\",\"temporaryPassword\":\"" + TEMPORARY + "\"}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail", containsString("existe déjà")));
        perform(post("/api/admin/accounts").session(admin),
                "{\"email\":\"court@test.local\",\"role\":\"USER\",\"temporaryPassword\":\"court\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail", containsString("au moins 12 caractères")));
        assertThat(accounts.findByEmail("court@test.local")).isEmpty();
    }

    @Test
    void disablingEndsTheSessionAtOnceAndBlocksLogin() throws Exception {
        long id = user("partant@test.local");
        MockHttpSession user = login("partant@test.local", PASSWORD);

        perform(post("/api/admin/accounts/" + id + "/disable").session(admin), "")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false));

        mvc.perform(get("/api/me").session(user)).andExpect(status().isUnauthorized());
        attemptLogin("partant@test.local", PASSWORD).andExpect(status().isUnauthorized());

        perform(post("/api/admin/accounts/" + id + "/enable").session(admin), "").andExpect(status().isOk());
        attemptLogin("partant@test.local", PASSWORD).andExpect(status().isOk());
    }

    @Test
    void anAdministratorCannotDisableOrDemoteThemself() throws Exception {
        long self = accounts.findByEmail(ADMIN).orElseThrow().getId();

        perform(post("/api/admin/accounts/" + self + "/disable").session(admin), "")
                .andExpect(status().isConflict());
        perform(put("/api/admin/accounts/" + self + "/role").session(admin), "{\"role\":\"USER\"}")
                .andExpect(status().isConflict());

        UserAccount unchanged = accounts.findByEmail(ADMIN).orElseThrow();
        assertThat(unchanged.isEnabled()).isTrue();
        assertThat(unchanged.getRole()).isEqualTo(Role.ADMIN);
    }

    @Test
    void roleChangeTakesEffectAtTheNextLogin() throws Exception {
        long id = user("promu@test.local");
        MockHttpSession before = login("promu@test.local", PASSWORD);

        perform(put("/api/admin/accounts/" + id + "/role").session(admin), "{\"role\":\"ADMIN\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("ADMIN"));

        mvc.perform(get("/api/me").session(before)).andExpect(status().isUnauthorized());
        MockHttpSession after = login("promu@test.local", PASSWORD);
        mvc.perform(get("/api/admin/accounts").session(after)).andExpect(status().isOk());
    }

    @Test
    void passwordResetByAnAdministratorIsTemporary() throws Exception {
        long id = user("oublieux@test.local");

        perform(post("/api/admin/accounts/" + id + "/password").session(admin),
                "{\"temporaryPassword\":\"" + TEMPORARY + "\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mustChangePassword").value(true));

        attemptLogin("oublieux@test.local", PASSWORD).andExpect(status().isUnauthorized());
        MockHttpSession user = login("oublieux@test.local", TEMPORARY);
        mvc.perform(get("/api/rules").session(user)).andExpect(status().isForbidden());
    }

    private long user(String email) {
        return accounts.save(new UserAccount(email, passwordEncoder.encode(PASSWORD), Role.USER)).getId();
    }

    private ResultActions attemptLogin(String email, String password) throws Exception {
        return mvc.perform(post("/login").param("username", email).param("password", password).with(realCsrf(mvc)));
    }

    private MockHttpSession login(String email, String password) throws Exception {
        return (MockHttpSession) attemptLogin(email, password)
                .andExpect(status().isOk())
                .andReturn().getRequest().getSession(false);
    }

    private ResultActions changeOwnPassword(MockHttpSession session, String current, String next) throws Exception {
        return perform(put("/api/me/password").session(session),
                "{\"currentPassword\":\"" + current + "\",\"newPassword\":\"" + next + "\"}");
    }

    private ResultActions perform(MockHttpServletRequestBuilder request, String json) throws Exception {
        return mvc.perform(request.with(realCsrf(mvc)).contentType(MediaType.APPLICATION_JSON).content(json));
    }
}
