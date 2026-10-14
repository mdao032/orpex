package com.orpex.rule;

import java.util.List;
import java.util.Optional;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;

/**
 * Accès aux lignes, toujours filtré par propriétaire (spécification §12) : ce dépôt n'expose volontairement aucune
 * méthode qui lirait les lignes de tout le monde ({@code findAll}, {@code findById}…).
 */
public interface RuleRepository extends Repository<Rule, Long> {

    Rule save(Rule rule);

    Optional<Rule> findByIdAndOwnerId(long id, long ownerId);

    /** Même lecture, avec verrou de ligne : deux activations simultanées de la même ligne s'attendent. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<Rule> findForUpdateByIdAndOwnerId(long id, long ownerId);

    List<Rule> findAllByOwnerIdAndActivatedAtIsNullOrderByBusinessIdAsc(long ownerId);

    Optional<Rule> findByOwnerIdAndBusinessId(long ownerId, String businessId);

    List<Rule> findAllByOwnerIdOrderByBusinessIdAsc(long ownerId);

    List<Rule> findAllByOwnerIdAndBusinessIdIn(long ownerId, Iterable<String> businessIds);

    long countByOwnerId(long ownerId);

    /**
     * Lignes de tous les propriétaires dont les envois dépendent des jours fériés (activées, non closes, jours ouvrés
     * uniquement). Seule lecture non filtrée par propriétaire : réservée au recalcul après un changement du calendrier
     * des jours fériés, décidé par un administrateur ({@link RescheduleService}).
     */
    @Query("""
            select r from Rule r where r.activatedAt is not null and r.businessDaysOnly = true
            and r.status = com.orpex.core.importing.RuleStatus.ACTIVE order by r.id""")
    List<Rule> findAllDependingOnHolidays();

    /** Envoie les écritures en attente à la base (pour détecter une violation d'unicité dans la transaction). */
    void flush();
}
