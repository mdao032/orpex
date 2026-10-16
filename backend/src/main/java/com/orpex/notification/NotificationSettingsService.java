package com.orpex.notification;

import com.orpex.audit.AuditEvent;
import com.orpex.audit.AuditLog;
import java.time.Clock;
import java.time.LocalTime;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Journée de travail réglée par l'administrateur (début et fin) ; le résumé quotidien part à l'heure de fin. */
@Service
public class NotificationSettingsService {

    public record Workday(LocalTime start, LocalTime end) {
    }

    private final NotificationSettingsRepository settings;
    private final Clock clock;
    private final AuditLog audit;

    public NotificationSettingsService(NotificationSettingsRepository settings, Clock clock, AuditLog audit) {
        this.settings = settings;
        this.clock = clock;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public Workday workday() {
        NotificationSettings s = settings.findById(NotificationSettings.SINGLE_ROW).orElseThrow();
        return new Workday(s.getWorkdayStart(), s.getWorkdayEnd());
    }

    @Transactional
    public Workday change(LocalTime start, LocalTime end, long adminId) {
        if (start == null || end == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Saisissez l'heure de début et l'heure de fin.");
        }
        if (!start.isBefore(end)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "L'heure de fin de journée doit être après l'heure de début.");
        }
        NotificationSettings s = settings.findForUpdateById(NotificationSettings.SINGLE_ROW).orElseThrow();
        String before = s.getWorkdayStart() + " – " + s.getWorkdayEnd();
        s.change(start, end, adminId, clock.instant());
        audit.record(AuditEvent.NOTIFICATION_SETTINGS_CHANGED, null, "SETTINGS", "journée de travail", before,
                start + " – " + end);
        return new Workday(start, end);
    }
}
