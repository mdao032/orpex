package com.orpex.api;

import static com.orpex.MockMvcSupport.login;
import static com.orpex.MockMvcSupport.send;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
        "orpex.bootstrap-admin.email= Admin@Test.Local ",
        "orpex.bootstrap-admin.password=secret-de-test"
})
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class AdminHolidayIT {

    private static final String PASSWORD = "mot-de-passe-solide";

    @Autowired
    MockMvc mvc;

    @Autowired
    UserAccountRepository accounts;

    @Autowired
    PasswordEncoder passwordEncoder;

    private MockHttpSession admin;

    @BeforeEach
    void loginAsAdmin() throws Exception {
        account("calendrier@test.local", Role.ADMIN);
        admin = login(mvc, "calendrier@test.local", PASSWORD);
    }

    @Test
    void onlyAdministratorsManageHolidays() throws Exception {
        account("lecteur@test.local", Role.USER);
        MockHttpSession user = login(mvc, "lecteur@test.local", PASSWORD);

        mvc.perform(get("/api/admin/holidays").session(user)).andExpect(status().isForbidden());
        send(mvc, post("/api/admin/holidays").session(user), "{\"day\":\"2027-05-01\",\"label\":\"Fête du travail\"}")
                .andExpect(status().isForbidden());
    }

    @Test
    void addListAndRemoveAHoliday() throws Exception {
        send(mvc, post("/api/admin/holidays").session(admin), "{\"day\":\"2027-07-14\",\"label\":\" Fête nationale \"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.day").value("2027-07-14"))
                .andExpect(jsonPath("$.label").value("Fête nationale"));
        send(mvc, post("/api/admin/holidays").session(admin), "{\"day\":\"2027-01-01\",\"label\":\"Jour de l'an\"}")
                .andExpect(status().isCreated());

        mvc.perform(get("/api/admin/holidays").session(admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.day == '2027-01-01')].label").value("Jour de l'an"))
                .andExpect(jsonPath("$[?(@.day == '2027-07-14')]").exists());

        send(mvc, delete("/api/admin/holidays/2027-07-14").session(admin), "").andExpect(status().isNoContent());
        mvc.perform(get("/api/admin/holidays").session(admin))
                .andExpect(jsonPath("$[?(@.day == '2027-07-14')]").doesNotExist());
    }

    @Test
    void refusesADuplicateDayAnEmptyLabelAndAnUnknownDay() throws Exception {
        send(mvc, post("/api/admin/holidays").session(admin), "{\"day\":\"2027-11-11\",\"label\":\"Armistice\"}")
                .andExpect(status().isCreated());

        send(mvc, post("/api/admin/holidays").session(admin), "{\"day\":\"2027-11-11\",\"label\":\"Encore\"}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("Le 11/11/2027 est déjà un jour férié."));
        send(mvc, post("/api/admin/holidays").session(admin), "{\"day\":\"2027-12-25\",\"label\":\"  \"}")
                .andExpect(status().isBadRequest());
        send(mvc, delete("/api/admin/holidays/2027-08-15").session(admin), "")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("Le 15/08/2027 n'est pas un jour férié."));
    }

    @Test
    void frenchHolidaysOfAYearAreAddedOnceWithoutTouchingExistingDays() throws Exception {
        send(mvc, post("/api/admin/holidays").session(admin), "{\"day\":\"2028-12-25\",\"label\":\"Noël (siège)\"}")
                .andExpect(status().isCreated());

        send(mvc, post("/api/admin/holidays/french/2028").session(admin), "")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.added.length()").value(10))
                .andExpect(jsonPath("$.alreadyPresent").value(1))
                .andExpect(jsonPath("$.added[?(@.day == '2028-04-17')].label").value("Lundi de Pâques"));
        send(mvc, post("/api/admin/holidays/french/2028").session(admin), "")
                .andExpect(jsonPath("$.added.length()").value(0))
                .andExpect(jsonPath("$.alreadyPresent").value(11));

        mvc.perform(get("/api/admin/holidays").session(admin))
                .andExpect(jsonPath("$[?(@.day == '2028-12-25')].label").value("Noël (siège)"));
        send(mvc, post("/api/admin/holidays/french/1999").session(admin), "")
                .andExpect(status().isBadRequest());
    }

    private void account(String email, Role role) {
        if (accounts.findByEmail(email).isEmpty()) {
            accounts.save(new UserAccount(email, passwordEncoder.encode(PASSWORD), role));
        }
    }
}
