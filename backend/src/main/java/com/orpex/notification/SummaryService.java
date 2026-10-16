package com.orpex.notification;

import com.orpex.account.SummaryFrequency;
import com.orpex.account.UserAccount;
import com.orpex.account.UserAccountRepository;
import com.orpex.audit.AuditEvent;
import com.orpex.audit.AuditLog;
import com.orpex.config.OrpexProperties;
import com.orpex.core.calendar.BusinessCalendar;
import com.orpex.core.mail.MailGateway;
import com.orpex.core.mail.MailMessage;
import com.orpex.core.mail.MailSendException;
import com.orpex.holiday.HolidayService;
import com.orpex.mail.MailSenderProvider;
import com.orpex.notification.NotificationSettingsService.Workday;
import com.orpex.rule.Occurrence;
import com.orpex.rule.OccurrenceRepository;
import com.orpex.rule.OccurrenceState;
import com.orpex.rule.Rule;
import com.orpex.rule.RuleRepository;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Résumé des relances (spécification §11), à la fréquence choisie par chaque utilisateur :
 * <ul>
 *   <li>chaque jour ouvré à la fin de la journée de travail : le jour même, et les envois prévus le lendemain
 *   (calendaire) ;</li>
 *   <li>chaque semaine, le dernier jour ouvré de la semaine à la fin de la journée : la semaine écoulée, et la
 *   semaine suivante ;</li>
 *   <li>chaque semaine, le premier jour ouvré de la semaine au début de la journée : la semaine précédente, et la
 *   semaine qui commence.</li>
 * </ul>
 * Un résumé n'est envoyé que s'il y a des envois sur ces périodes, une fois par jour au plus ; un mail refusé par le
 * relais est retenté au passage suivant.
 */
@Service
public class SummaryService {

    private static final Logger log = LoggerFactory.getLogger(SummaryService.class);
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("dd/MM HH:mm");
    private static final Set<OccurrenceState> PROBLEMS = Set.of(OccurrenceState.FAILED, OccurrenceState.TO_CHECK);

    /** Ce que couvre un résumé : envois passés {@code from}–{@code to}, envois prévus {@code nextFrom}–{@code nextTo}. */
    record Span(LocalDate from, LocalDate to, LocalDate nextFrom, LocalDate nextTo, boolean weekly) {
    }

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

    public SummaryService(NotificationSettingsService settings, NotificationSettingsRepository summaries,
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

    /** Résumés dus à l'instant {@code now}. */
    public int sendDueSummaries(Instant now) {
        ZonedDateTime local = now.atZone(clock.getZone());
        LocalDate today = local.toLocalDate();
        BusinessCalendar calendar = holidays.businessCalendar();
        if (!calendar.isBusinessDay(today)) {
            return 0;
        }
        Workday workday = settings.workday();
        LocalDate monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        int sent = 0;
        for (Long ownerId : occurrences.findOwnersWithSendsBetween(monday.minusWeeks(1), monday.plusWeeks(2))) {
            UserAccount owner = accounts.findById(ownerId).orElse(null);
            if (owner == null || !owner.isEnabled() || summaries.dailySummarySent(ownerId, today)) {
                continue;
            }
            Span span = span(owner.getSummaryFrequency(), today, local.toLocalTime(), workday, calendar);
            if (span != null && summarize(owner, today, span, now)) {
                sent++;
            }
        }
        return sent;
    }

    /** Période du résumé dû aujourd'hui à cette heure pour cette fréquence ; nul s'il n'est pas dû. */
    static Span span(SummaryFrequency frequency, LocalDate today, LocalTime time, Workday workday,
            BusinessCalendar calendar) {
        LocalDate monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        LocalDate sunday = monday.plusDays(6);
        return switch (frequency) {
            case DAILY -> time.isBefore(workday.end()) ? null
                    : new Span(today, today, today.plusDays(1), today.plusDays(1), false);
            case WEEKLY_FRIDAY -> time.isBefore(workday.end()) || hasBusinessDay(today.plusDays(1), sunday, calendar)
                    ? null : new Span(monday, today, monday.plusWeeks(1), sunday.plusWeeks(1), true);
            case WEEKLY_MONDAY -> time.isBefore(workday.start()) || hasBusinessDay(monday, today.minusDays(1), calendar)
                    ? null : new Span(monday.minusWeeks(1), monday.minusDays(1), today, sunday, true);
        };
    }

    private static boolean hasBusinessDay(LocalDate from, LocalDate to, BusinessCalendar calendar) {
        return from.datesUntil(to.plusDays(1)).anyMatch(calendar::isBusinessDay);
    }

    private boolean summarize(UserAccount owner, LocalDate today, Span span, Instant now) {
        long ownerId = owner.getId();
        List<Occurrence> past = occurrences.findAllByCreatedByAndScheduledDateBetweenOrderByScheduledAtAsc(ownerId,
                span.from(), span.to());
        List<Occurrence> next = occurrences.findAllByCreatedByAndScheduledDateBetweenOrderByScheduledAtAsc(ownerId,
                span.nextFrom(), span.nextTo());
        long sent = past.stream().filter(o -> o.getState() == OccurrenceState.SENT).count();
        List<Occurrence> problems = past.stream().filter(o -> PROBLEMS.contains(o.getState())).toList();
        long notSent = past.stream().filter(o -> o.getState() == OccurrenceState.MISSED
                || o.getState() == OccurrenceState.NOT_VALIDATED).count();
        long planned = next.stream().filter(o -> OccurrenceState.NOT_STARTED.contains(o.getState())).count();
        if (sent + problems.size() + notSent + planned == 0) {
            return false;
        }

        String period = span.weekly()
                ? "la semaine du " + DAY.format(span.from()) + " au " + DAY.format(span.to())
                : "la journée du " + DAY.format(span.from());
        String upcoming = span.weekly()
                ? "du " + DAY.format(span.nextFrom()) + " au " + DAY.format(span.nextTo())
                : "demain (" + DAY.format(span.nextFrom()) + ")";
        StringBuilder body = new StringBuilder("Bonjour,\n\nRésumé de vos relances pour ").append(period)
                .append(" :\n\n- envoyées : ").append(sent)
                .append("\n- en échec ou à vérifier : ").append(problems.size())
                .append("\n- non parties (manquées ou non validées) : ").append(notSent)
                .append("\n- prévues ").append(upcoming).append(" : ").append(planned).append('\n');
        if (!problems.isEmpty()) {
            body.append("\nÀ traiter :\n");
            for (Occurrence o : problems) {
                Rule rule = rules.findByIdAndOwnerId(o.getRuleId(), ownerId).orElse(null);
                body.append("- ").append(rule == null ? "?" : rule.getBusinessId()).append(" → ")
                        .append(rule == null ? "?" : rule.getRecipientEmail()).append(", prévu le ")
                        .append(WHEN.format(o.getScheduledAt().atZone(clock.getZone()))).append(" : ")
                        .append(o.getState() == OccurrenceState.TO_CHECK ? "à vérifier" : "échec").append('\n');
            }
        }
        body.append("\nDétail : ").append(properties.appUrl().replaceAll("/+$", "")).append("/lignes")
                .append("\n\nFréquence de ce résumé : modifiable dans « Mon compte ».");
        String subject = "ORPEX : résumé de " + period + " (" + sent + " envoyée(s)"
                + (problems.isEmpty() ? "" : ", " + problems.size() + " à traiter") + ")";
        try {
            gateway.send(new MailMessage(senders.current().from(), owner.getEmail(), subject, body.toString()));
        } catch (MailSendException e) {
            log.warn("Résumé non envoyé à {} (nouvel essai au passage suivant) : {}", owner.getEmail(),
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
