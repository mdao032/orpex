package com.orpex.api;

import com.orpex.account.AccountService;
import com.orpex.account.SummaryFrequency;
import com.orpex.account.UserAccount;
import com.orpex.account.UserAccountRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.time.Clock;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Permet au front de savoir qui est connecté (200) ou s'il doit afficher la page de connexion (401). */
@RestController
@RequestMapping("/api/me")
public class MeController {

    /** {@code timezone} : fuseau de l'installation, dans lequel l'écran affiche les heures (pas celui du navigateur). */
    public record MeResponse(String email, List<String> roles, boolean mustChangePassword, String timezone,
                             SummaryFrequency summaryFrequency) {
    }

    public record SummaryRequest(SummaryFrequency frequency) {
    }

    public record ChangePasswordRequest(@NotBlank String currentPassword, @NotBlank String newPassword) {
    }

    private final UserAccountRepository accounts;
    private final AccountService accountService;
    private final Clock clock;

    public MeController(UserAccountRepository accounts, AccountService accountService, Clock clock) {
        this.accounts = accounts;
        this.accountService = accountService;
        this.clock = clock;
    }

    @GetMapping
    public MeResponse me(Authentication authentication) {
        List<String> roles = authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .toList();
        var account = accounts.findByEmail(UserAccount.normalizeEmail(authentication.getName()));
        boolean mustChangePassword = account.map(UserAccount::isMustChangePassword).orElse(false);
        SummaryFrequency frequency = account.map(UserAccount::getSummaryFrequency).orElse(SummaryFrequency.DAILY);
        return new MeResponse(authentication.getName(), roles, mustChangePassword, clock.getZone().getId(), frequency);
    }

    /** Fréquence du résumé de ses relances : chaque jour ouvré, ou chaque semaine (vendredi soir ou lundi matin). */
    @PutMapping("/summary-frequency")
    public SummaryRequest changeSummaryFrequency(@RequestBody SummaryRequest request, Authentication authentication) {
        return new SummaryRequest(accountService.changeSummaryFrequency(authentication.getName(), request.frequency()));
    }

    @PutMapping("/password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void changePassword(@Valid @RequestBody ChangePasswordRequest request, Authentication authentication) {
        accountService.changeOwnPassword(authentication.getName(), request.currentPassword(), request.newPassword());
    }
}
