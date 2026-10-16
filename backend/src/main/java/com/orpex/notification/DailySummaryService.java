package com.orpex.notification;

import com.orpex.account.UserAccount;
import com.orpex.account.UserAccountRepository;
import com.orpex.audit.AuditEvent;
import com.orpex.audit.AuditLog;
import com.orpex.config.OrpexProperties;
import com.orpex.core.mail.MailGateway;
import com.orpex.core.mail.MailMessage;
import com.orpex.core.mail.MailSendException;
import com.orpex.holiday.HolidayService;
import com.orpex.mail.MailSenderProvider;
import com.orpex.rule.Occurrence;
import com.orpex.rule.OccurrenceRepository;
import com.orpex.rule.OccurrenceState;
import com.orpex.rule.Rule;
import com.orpex.rule.RuleRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Résumé quotidien (spécification §11) : chaque jour ouvré, à l'heure de fin de journée réglée par l'administrateur,
 * un mail par propriétaire ayant des envois aujourd'hui ou demain : envoyés, échoués, prévus demain (lendemain
 * calendaire). Un seul résumé par jour ; un mail refusé par le relais est retenté au passage suivant.
 */
@Service
public class DailySummaryService {

    private static final Logger log = LoggerFactory.getLogger(DailySummaryService.class);
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");
    private static final Set<OccurrenceState> PROBLEMS = Set.of(OccurrenceState.FAILED, OccurrenceState.TO_CHECK);

    private final NotificationSettingsService settings;
    private final NotificationSettingsRepository summaries;
    private final OccurrenceRepository occurrences;
    private final RuleRepository rules;
    private final UserAccountRepository accounts;
    private final HolidayService holidays;
    private final MailGateway gateway;
    private final MailSenderProvider senders;
    private final TransactionTemplate transaction;
    private final OrpexProperties properties;
    private final Clock clock;
    private final AuditLog audit;

    public DailySummaryService(NotificationSettingsService settings, NotificationSettingsRepository summaries,
            OccurrenceRepository occurrences, RuleRepository rules, UserAccountRepository accounts,
            HolidayService holidays, MailGateway gateway, MailSenderProvider senders, TransactionTemplate transaction,
            OrpexProperties properties, Clock clock, AuditLog audit) {
        this.settings = settings;
        this.summaries = summaries;
        this.occurrences = occurrences;
        this.rules = rules;
        this.accounts = accounts;
        this.holidays = holidays;
        this.gateway = gateway;
        this.senders = senders;
        this.transaction = transaction;
        this.properties = properties;
        this.clock = clock;
        this.audit = audit;
    }

    /** Envoie les résumés dus maintenant ; renvoie le nombre de mails partis. */
    public int sendDueSummaries() {
        return sendDueSummaries(clock.instant());
    }

    /** Résumés dus à l'instant {@code now} (jour ouvré, heure de fin de journée atteinte). */
    public int sendDueSummaries(Instant now) {
        ZonedDateTime local = now.atZone(clock.getZone());
        LocalDate today = local.toLocalDate();
        if (!holidays.businessCalendar().isBusinessDay(today)
                || local.toLocalTime().isBefore(settings.workday().end())) {
            return 0;
        }
        LocalDate tomorrow = today.plusDays(1);
        int sent = 0;
        for (Long ownerId : occurrences.findOwnersWithSendsOn(List.of(today, tomorrow))) {
            if (!summaries.dailySummarySent(ownerId, today) && summarize(ownerId, today, tomorrow, now)) {
                sent++;
            }
        }
        return sent;
    }

    private boolean summarize(long ownerId, LocalDate today, LocalDate tomorrow, Instant now) {
        UserAccount owner = accounts.findById(ownerId).orElse(null);
        if (owner == null || !owner.isEnabled()) {
            return false;
        }
        List<Occurrence> sends = occurrences.findAllByCreatedByAndScheduledDateInOrderByScheduledAtAsc(ownerId,
                List.of(today, tomorrow));
        List<Occurrence> ofToday = sends.stream().filter(o -> o.getScheduledDate().equals(today)).toList();
        long sentToday = ofToday.stream().filter(o -> o.getState() == OccurrenceState.SENT).count();
        List<Occurrence> problems = ofToday.stream().filter(o -> PROBLEMS.contains(o.getState())).toList();
        long notSent = ofToday.stream().filter(o -> o.getState() == OccurrenceState.MISSED
                || o.getState() == OccurrenceState.NOT_VALIDATED).count();
        long plannedTomorrow = sends.stream().filter(o -> o.getScheduledDate().equals(tomorrow)
                && OccurrenceState.NOT_STARTED.contains(o.getState())).count();
        if (sentToday + problems.size() + notSent + plannedTomorrow == 0) {
            return false;
        }

        StringBuilder body = new StringBuilder("Bonjour,\n\nRésumé de vos relances du ").append(DAY.format(today))
                .append(" :\n\n- envoyées : ").append(sentToday)
                .append("\n- en échec ou à vérifier : ").append(problems.size())
                .append("\n- non parties (manquées ou non validées) : ").append(notSent)
                .append("\n- prévues demain (").append(DAY.format(tomorrow)).append(") : ").append(plannedTomorrow)
                .append('\n');
        if (!problems.isEmpty()) {
            body.append("\nÀ traiter :\n");
            for (Occurrence o : problems) {
                Rule rule = rules.findByIdAndOwnerId(o.getRuleId(), ownerId).orElse(null);
                body.append("- ").append(rule == null ? "?" : rule.getBusinessId()).append(" → ")
                        .append(rule == null ? "?" : rule.getRecipientEmail()).append(", prévu à ")
                        .append(TIME.format(o.getScheduledAt().atZone(clock.getZone()))).append(" : ")
                        .append(o.getState() == OccurrenceState.TO_CHECK ? "à vérifier" : "échec").append('\n');
            }
        }
        body.append("\nDétail : ").append(properties.appUrl().replaceAll("/+$", "")).append("/lignes");
        String subject = "ORPEX : résumé du " + DAY.format(today) + " (" + sentToday + " envoyée(s)"
                + (problems.isEmpty() ? "" : ", " + problems.size() + " à traiter") + ")";
        try {
            gateway.send(new MailMessage(senders.current().from(), owner.getEmail(), subject, body.toString()));
        } catch (MailSendException e) {
            log.warn("Résumé quotidien non envoyé à {} (nouvel essai au passage suivant) : {}", owner.getEmail(),
                    e.getMessage());
            return false;
        }
        transaction.executeWithoutResult(status -> {
            summaries.reserveDailySummary(ownerId, today, now);
            audit.record(AuditEvent.DAILY_SUMMARY_SENT, ownerId, "USER", owner.getEmail(), null, subject);
        });
        return true;
    }
}
