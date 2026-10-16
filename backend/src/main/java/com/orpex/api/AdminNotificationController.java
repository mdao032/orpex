package com.orpex.api;

import com.orpex.account.CurrentUser;
import com.orpex.notification.NotificationSettingsService;
import com.orpex.notification.NotificationSettingsService.Workday;
import java.time.LocalTime;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Journée de travail (administrateur) : le résumé quotidien part à l'heure de fin. */
@RestController
@RequestMapping("/api/admin/workday")
public class AdminNotificationController {

    public record WorkdayRequest(LocalTime start, LocalTime end) {
    }

    private final NotificationSettingsService settings;
    private final CurrentUser currentUser;

    public AdminNotificationController(NotificationSettingsService settings, CurrentUser currentUser) {
        this.settings = settings;
        this.currentUser = currentUser;
    }

    @GetMapping
    public Workday get() {
        return settings.workday();
    }

    @PutMapping
    public Workday change(@RequestBody WorkdayRequest request, Authentication authentication) {
        return settings.change(request.start(), request.end(), currentUser.id(authentication));
    }
}
