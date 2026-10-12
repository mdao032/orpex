package com.orpex.api;

import com.orpex.account.AccountService;
import com.orpex.account.Role;
import com.orpex.account.UserAccount;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Administration des comptes ; réservé au rôle ADMIN par la configuration de sécurité ({@code /api/admin/**}). */
@RestController
@RequestMapping("/api/admin/accounts")
public class AdminAccountController {

    public record AccountView(long id, String email, Role role, boolean enabled, boolean locked,
            boolean mustChangePassword, Instant lastLoginAt, Instant createdAt) {

        static AccountView of(UserAccount account) {
            return new AccountView(account.getId(), account.getEmail(), account.getRole(), account.isEnabled(),
                    account.isLockedAt(Instant.now()), account.isMustChangePassword(), account.getLastLoginAt(),
                    account.getCreatedAt());
        }
    }

    public record CreateAccountRequest(@NotBlank String email, @NotNull Role role, @NotBlank String temporaryPassword) {
    }

    public record ResetPasswordRequest(@NotBlank String temporaryPassword) {
    }

    public record ChangeRoleRequest(@NotNull Role role) {
    }

    private final AccountService accountService;

    public AdminAccountController(AccountService accountService) {
        this.accountService = accountService;
    }

    @GetMapping
    public List<AccountView> list() {
        return accountService.list().stream().map(AccountView::of).toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public AccountView create(@Valid @RequestBody CreateAccountRequest request) {
        return AccountView.of(accountService.create(request.email(), request.role(), request.temporaryPassword()));
    }

    @PostMapping("/{id}/disable")
    public AccountView disable(@PathVariable long id, Authentication actor) {
        return AccountView.of(accountService.disable(actor.getName(), id));
    }

    @PostMapping("/{id}/enable")
    public AccountView enable(@PathVariable long id) {
        return AccountView.of(accountService.enable(id));
    }

    @PutMapping("/{id}/role")
    public AccountView changeRole(@PathVariable long id, @Valid @RequestBody ChangeRoleRequest request,
            Authentication actor) {
        return AccountView.of(accountService.changeRole(actor.getName(), id, request.role()));
    }

    @PostMapping("/{id}/password")
    public AccountView resetPassword(@PathVariable long id, @Valid @RequestBody ResetPasswordRequest request) {
        return AccountView.of(accountService.resetPassword(id, request.temporaryPassword()));
    }
}
