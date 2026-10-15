package com.orpex.rule;

import java.time.Instant;
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
