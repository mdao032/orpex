package com.orpex.rule;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/**
 * Envois d'une ligne. La table n'a pas de propriétaire : l'identifiant de ligne passé ici doit toujours venir d'une
 * lecture filtrée par propriétaire ({@link RuleRepository}). Aucune méthode de suppression (interdite en base).
 */
public interface OccurrenceRepository extends Repository<Occurrence, Long> {

    Occurrence save(Occurrence occurrence);

    List<Occurrence> findAllByRuleIdOrderByScheduledDateAsc(long ruleId);

    long countByRuleId(long ruleId);

    List<Occurrence> findAllByRuleIdAndStateIn(long ruleId, Collection<OccurrenceState> states);

    Optional<Occurrence> findById(long id);

    List<Occurrence> findAllByIdIn(Collection<Long> ids);

    /**
     * Worker : réserve les envois dus qui peuvent partir (prévus ou validés, ligne non close ni en pause) : heure
     * prévue atteinte depuis moins de {@code oldest}, ou nouvelle tentative arrivée à échéance. {@code SKIP LOCKED} :
     * un envoi déjà réservé par un autre worker est ignoré, il ne peut donc jamais partir deux fois.
     */
    @Query(nativeQuery = true, value = """
            select o.id from occurrences o join rules r on r.id = o.rule_id
            where o.state in ('PLANNED', 'VALIDATED')
              and (o.next_attempt_at <= :now
                   or (o.next_attempt_at is null and o.scheduled_at <= :now and o.scheduled_at >= :oldest))
              and r.status = 'ACTIVE' and not r.paused
            order by coalesce(o.next_attempt_at, o.scheduled_at), o.id
            limit :limit
            for update of o skip locked""")
    List<Long> lockDue(@Param("now") Instant now, @Param("oldest") Instant oldest, @Param("limit") int limit);

    /**
     * Envois en attente de validation dont l'heure est venue : sans validation, rien ne part, et un envoi non validé
     * ne part jamais en retard (spécification §7).
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update Occurrence o set o.state = :notValidated, o.updatedAt = :now
            where o.state = :awaiting and o.scheduledAt <= :now""")
    int markExpiredAwaitingAsNotValidated(@Param("now") Instant now,
            @Param("awaiting") OccurrenceState awaiting, @Param("notValidated") OccurrenceState notValidated);

    /**
     * Envois du jour d'un propriétaire concernés par la validation (lignes actives, non en pause) : en attente, validés,
     * ou décochés par lui (pas ceux devenus « non validés » faute de validation).
     */
    @Query("""
            select o from Occurrence o, Rule r
            where r.id = o.ruleId and r.ownerId = :ownerId and r.status = com.orpex.core.importing.RuleStatus.ACTIVE
              and r.paused = false and r.dailyValidation = true and o.scheduledDate = :day
              and (o.state in (com.orpex.rule.OccurrenceState.AWAITING_VALIDATION,
                               com.orpex.rule.OccurrenceState.VALIDATED)
                   or (o.state = com.orpex.rule.OccurrenceState.NOT_VALIDATED and o.validatedBy is not null))
            order by o.scheduledAt, o.id""")
    List<Occurrence> findForDailyValidation(@Param("ownerId") long ownerId, @Param("day") LocalDate day);

    /**
     * Récapitulatif de validation : envois du jour en attente, pas encore rappelés, de lignes actives non en pause
     * (tous propriétaires : opération système, chaque propriétaire ne reçoit que les siens).
     */
    @Query("""
            select o from Occurrence o, Rule r
            where r.id = o.ruleId and r.status = com.orpex.core.importing.RuleStatus.ACTIVE and r.paused = false
              and o.state = :awaiting and o.scheduledDate = :day and o.remindedAt is null
            order by o.scheduledAt""")
    List<Occurrence> findAwaitingNotReminded(@Param("day") LocalDate day,
            @Param("awaiting") OccurrenceState awaiting);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update Occurrence o set o.remindedAt = :now where o.id in :ids")
    int markReminded(@Param("ids") Collection<Long> ids, @Param("now") Instant now);

    /** Résumé quotidien (requête système) : propriétaires ayant des envois l'un de ces jours. */
    @Query("select distinct o.createdBy from Occurrence o where o.scheduledDate in :days")
    List<Long> findOwnersWithSendsOn(@Param("days") Collection<LocalDate> days);

    List<Occurrence> findAllByCreatedByAndScheduledDateInOrderByScheduledAtAsc(long createdBy,
            Collection<LocalDate> days);

    /** Alerte d'échec (requête système) : envois en échec ou à vérifier pas encore signalés. */
    @Query(nativeQuery = true, value = """
            select * from occurrences where state in ('FAILED', 'TO_CHECK') and alerted_at is null order by id""")
    List<Occurrence> findNotAlertedProblems();

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(nativeQuery = true, value = "update occurrences set alerted_at = :now where id in (:ids)")
    int markAlerted(@Param("ids") Collection<Long> ids, @Param("now") Instant now);

    /** Nombre d'envois déjà partis, par ligne : « nombre de relances déjà envoyées » (spécification §7). */
    @Query("""
            select o.ruleId, count(o) from Occurrence o
            where o.ruleId in :ruleIds and o.state = :sent group by o.ruleId""")
    List<Object[]> countByRuleIdsAndState(@Param("ruleIds") Collection<Long> ruleIds,
            @Param("sent") OccurrenceState sent);

    /**
     * Envois restés « en cours » au-delà du délai d'envoi : l'application s'est arrêtée pendant l'envoi, on ne sait
     * pas si le serveur l'a accepté. Ils passent « à vérifier » et ne seront jamais renvoyés automatiquement.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update Occurrence o set o.state = :toCheck, o.lastError = :reason, o.issue = :interrupted,
                o.updatedAt = :now
            where o.state = :sending and o.updatedAt < :before""")
    int markInterruptedAsToCheck(@Param("before") Instant before, @Param("now") Instant now,
            @Param("reason") String reason, @Param("sending") OccurrenceState sending,
            @Param("toCheck") OccurrenceState toCheck, @Param("interrupted") SendIssue interrupted);

    /**
     * Worker : envois prévus ou validés dont l'heure est dépassée de trop pour partir encore (arrêt du service). Un
     * envoi en cours de nouvelles tentatives n'est pas concerné : ses tentatives sont espacées volontairement.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update Occurrence o set o.state = :missed, o.updatedAt = :now
            where o.state in :sendable and o.scheduledAt < :oldest and o.nextAttemptAt is null""")
    int markTooLateAsMissed(@Param("now") Instant now, @Param("oldest") Instant oldest,
            @Param("sendable") Collection<OccurrenceState> sendable, @Param("missed") OccurrenceState missed);

    /** Envois d'une ligne dont l'heure est passée sans qu'ils partent (pause) : manqués, jamais rattrapés. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update Occurrence o set o.state = :missed, o.updatedAt = :now
            where o.ruleId = :ruleId and o.state in :notStarted and o.scheduledAt < :now""")
    int markMissedBefore(@Param("ruleId") long ruleId, @Param("now") Instant now,
            @Param("notStarted") Collection<OccurrenceState> notStarted, @Param("missed") OccurrenceState missed);

    /**
     * Même chose pour toute l'installation, à la fin d'une pause générale. Seule opération sans filtre par ligne :
     * réservée à l'administrateur ({@link SendingControlService}).
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update Occurrence o set o.state = :missed, o.updatedAt = :now
            where o.state in :notStarted and o.scheduledAt < :now""")
    int markAllMissedBefore(@Param("now") Instant now, @Param("notStarted") Collection<OccurrenceState> notStarted,
            @Param("missed") OccurrenceState missed);
}
