package com.orpex.rule;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
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
