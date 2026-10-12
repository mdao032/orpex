package com.orpex.account;

import static com.orpex.CsrfSupport.realCsrf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.orpex.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
        "orpex.bootstrap-admin.email= Admin@Test.Local ",
        "orpex.bootstrap-admin.password=secret-de-test"
})
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class AccountIT {

    @Autowired
    UserAccountRepository accounts;

    @Autowired
    DatabaseUserDetailsService userDetailsService;

    @Autowired
    BootstrapAdminInitializer bootstrapAdminInitializer;

    @Autowired
    PasswordEncoder passwordEncoder;

    @Autowired
    MockMvc mvc;

    @Test
    void emptyDatabaseGetsTheConfiguredAdminWithHashedPassword() {
        UserAccount admin = accounts.findByEmail("admin@test.local").orElseThrow();

        assertThat(admin.getRole()).isEqualTo(Role.ADMIN);
        assertThat(admin.isEnabled()).isTrue();
        assertThat(admin.getPasswordHash()).startsWith("{argon2}");
        assertThat(admin.isMustChangePassword()).as("mot de passe connu de l'IT : provisoire").isTrue();
        assertThat(passwordEncoder.matches("secret-de-test", admin.getPasswordHash())).isTrue();
    }

    @Test
    void bootstrapNeverRunsAgainOnceAccountsExist() {
        long before = accounts.count();

        bootstrapAdminInitializer.run(new DefaultApplicationArguments());

        assertThat(accounts.count()).isEqualTo(before);
    }

    @Test
    void loginLooksUpTheAccountWhateverTheEmailCase() {
        UserDetails user = userDetailsService.loadUserByUsername("ADMIN@test.local");

        assertThat(user.getUsername()).isEqualTo("admin@test.local");
        assertThat(user.getAuthorities()).extracting(GrantedAuthority::getAuthority).containsExactly("ROLE_ADMIN");
        assertThat(user.isEnabled()).isTrue();
    }

    @Test
    void disabledAccountCannotLogIn() {
        UserAccount account = new UserAccount("desactive@test.local", passwordEncoder.encode("x"), Role.USER);
        account.disable();
        accounts.save(account);

        assertThat(userDetailsService.loadUserByUsername("desactive@test.local").isEnabled()).isFalse();
    }

    @Test
    void unknownAccountIsRejected() {
        assertThatThrownBy(() -> userDetailsService.loadUserByUsername("inconnu@test.local"))
                .isInstanceOf(UsernameNotFoundException.class);
    }

    @Test
    void bcryptHashIsReplacedByArgon2idAfterSuccessfulLogin() throws Exception {
        String bcryptHash = "{bcrypt}" + new BCryptPasswordEncoder().encode("ancien-mot-de-passe");
        accounts.save(new UserAccount("ancien@test.local", bcryptHash, Role.USER));

        mvc.perform(post("/login").param("username", "ancien@test.local").param("password", "faux").with(realCsrf(mvc)))
                .andExpect(status().isUnauthorized());
        assertThat(accounts.findByEmail("ancien@test.local").orElseThrow().getPasswordHash()).isEqualTo(bcryptHash);

        mvc.perform(post("/login").param("username", "ancien@test.local").param("password", "ancien-mot-de-passe").with(realCsrf(mvc)))
                .andExpect(status().isOk());
        String upgraded = accounts.findByEmail("ancien@test.local").orElseThrow().getPasswordHash();
        assertThat(upgraded).startsWith("{argon2}");
        assertThat(passwordEncoder.matches("ancien-mot-de-passe", upgraded)).isTrue();
    }
}
