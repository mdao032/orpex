package com.orpex.audit;

import com.orpex.rule.OccurrenceState;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Consultation du journal (spécification §11) : l'utilisateur voit ses relances et ses propres actions,
 * l'administrateur tout. Les envois (écrits en JSON par la base) sont mis en phrase lisible.
 */
@Service
public class AuditQueryService {

    /** Ligne lisible du journal ; {@code actor} : email de l'auteur, nul pour le système. */
    public record AuditView(long id, Instant occurredAt, String actor, AuditEvent event, String eventLabel,
                            String objectType, String objectId, String before, String after) {
    }

    /** Lignes au plus à l'écran, et dans un export. */
    public static final int SCREEN_LIMIT = 500;
    public static final int EXPORT_LIMIT = 100_000;

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {
    };
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final DateTimeFormatter DAY_TIME = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");
    private static final Map<String, String> STATES = Map.of(
            "PLANNED", "Prévu", "AWAITING_VALIDATION", "En attente de validation", "VALIDATED", "Validé",
            "SENDING", "En cours d'envoi", "SENT", "Envoyé", "FAILED", "Échec", "TO_CHECK", "À vérifier",
            "NOT_VALIDATED", "Non validé", "CANCELLED", "Annulé", "MISSED", "Manqué");

    private final AuditRepository entries;
    private final Clock clock;

    public AuditQueryService(AuditRepository entries, Clock clock) {
        this.entries = entries;
        this.clock = clock;
    }

    /** Journal des relances de l'utilisateur et de ses propres actions, du plus récent au plus ancien. */
    @Transactional(readOnly = true)
    public List<AuditView> forUser(long userId, LocalDate from, LocalDate to, int limit) {
        return views(entries.findForUser(userId, start(from), end(to), PageRequest.of(0, limit)));
    }

    /** Administrateur : tout le journal. */
    @Transactional(readOnly = true)
    public List<AuditView> all(LocalDate from, LocalDate to, int limit) {
        return views(entries.findAll(start(from), end(to), PageRequest.of(0, limit)));
    }

    private Instant start(LocalDate from) {
        return from == null ? Instant.EPOCH : from.atStartOfDay(clock.getZone()).toInstant();
    }

    private Instant end(LocalDate to) {
        if (to == null) {
            return Instant.parse("9999-01-01T00:00:00Z");
        }
        return to.plusDays(1).atStartOfDay(clock.getZone()).toInstant();
    }

    public static void checkRange(LocalDate from, LocalDate to) {
        if (from != null && to != null && to.isBefore(from)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "La date de fin précède la date de début.");
        }
    }

    private List<AuditView> views(List<Object[]> rows) {
        List<AuditView> views = new ArrayList<>(rows.size());
        for (Object[] row : rows) {
            AuditEntry e = (AuditEntry) row[0];
            String actor = (String) row[1];
            String before = e.getOldValue();
            String after = e.getNewValue();
            if (e.getEventType() == AuditEvent.SEND_STATE_CHANGED) {
                before = state(before);
                after = describeSend(after);
            }
            views.add(new AuditView(e.getId(), e.getOccurredAt(), actor, e.getEventType(), e.getEventType().label(),
                    e.getObjectType(), e.getObjectId(), before, after));
        }
        return views;
    }

    /** « A-1, dest@exemple.ml, prévu le 22/11/2027 09:00 : Envoyé le 22/11/2027 09:00 (erreur : …) ». */
    private String describeSend(String json) {
        if (json == null) {
            return null;
        }
        Map<String, Object> d;
        try {
            d = JSON.readValue(json, MAP);
        } catch (JacksonException e) {
            return json;
        }
        StringBuilder text = new StringBuilder();
        text.append(d.get("relance")).append(", ").append(d.get("destinataire"));
        String planned = time(d.get("heure_prevue"));
        text.append(", prévu le ").append(planned != null ? planned : day(d.get("jour_prevu")));
        text.append(" : ").append(state((String) d.get("etat")));
        String sentAt = time(d.get("envoye_le"));
        if (sentAt != null) {
            text.append(" le ").append(sentAt);
        }
        Object attempts = d.get("tentatives");
        if (attempts instanceof Number n && n.intValue() > 1) {
            text.append(" (").append(n.intValue()).append(" tentatives)");
        }
        if (d.get("erreur") != null && !OccurrenceState.SENT.name().equals(d.get("etat"))) {
            text.append(" ; erreur : ").append(d.get("erreur"));
        }
        return text.toString();
    }

    private static String state(String code) {
        return code == null ? null : STATES.getOrDefault(code, code);
    }

    private String time(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return DAY_TIME.format(OffsetDateTime.parse(value.toString()).atZoneSameInstant(zone()));
        } catch (RuntimeException e) {
            return value.toString();
        }
    }

    private static String day(Object value) {
        try {
            return value == null ? "?" : DAY.format(LocalDate.parse(value.toString()));
        } catch (RuntimeException e) {
            return value.toString();
        }
    }

    private ZoneId zone() {
        return clock.getZone();
    }
}
