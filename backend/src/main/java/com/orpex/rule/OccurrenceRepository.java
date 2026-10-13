package com.orpex.rule;

import java.util.Collection;
import java.util.List;
import org.springframework.data.repository.Repository;

/**
 * Envois d'une ligne. La table n'a pas de propriétaire : l'identifiant de ligne passé ici doit toujours venir d'une
 * lecture filtrée par propriétaire ({@link RuleRepository}). Aucune méthode de suppression (interdite en base).
 */
public interface OccurrenceRepository extends Repository<Occurrence, Long> {

    Occurrence save(Occurrence occurrence);

    List<Occurrence> findAllByRuleIdOrderByScheduledDateAsc(long ruleId);

    long countByRuleId(long ruleId);

    List<Occurrence> findAllByRuleIdAndStateIn(long ruleId, Collection<OccurrenceState> states);
}
