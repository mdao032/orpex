package com.orpex.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.orpex.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;

@SpringBootTest(properties = {
        "orpex.bootstrap-admin.email= Admin@Test.Local ",
        "orpex.bootstrap-admin.password=secret-de-test"
})
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

    @Test
    void emptyDatabaseGetsTheConfiguredAdminWithHashedPassword() {
        UserAccount admin = accounts.findByEmail("admin@test.local").orElseThrow();

        assertThat(admin.getRole()).isEqualTo(Role.ADMIN);
        assertThat(admin.isEnabled()).isTrue();
        assertThat(admin.getPasswordHash()).isNotEqualTo("secret-de-test");
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
}
