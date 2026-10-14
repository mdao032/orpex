package com.orpex.api;

import com.orpex.account.CurrentUser;
import com.orpex.mail.SmtpSettingsService;
import com.orpex.mail.SmtpSettingsService.Change;
import com.orpex.mail.SmtpSettingsService.View;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Relais SMTP ; réservé au rôle ADMIN ({@code /api/admin/**}). Le mot de passe n'est jamais renvoyé. */
@RestController
@RequestMapping("/api/admin/smtp")
public class AdminSmtpController {

    private final SmtpSettingsService settings;
    private final CurrentUser currentUser;

    public AdminSmtpController(SmtpSettingsService settings, CurrentUser currentUser) {
        this.settings = settings;
        this.currentUser = currentUser;
    }

    @GetMapping
    public View view() {
        return settings.view();
    }

    @PutMapping
    public View save(@RequestBody Change change, Authentication authentication) {
        return settings.save(change, currentUser.id(authentication));
    }
}
