package com.orpex.rule;

import java.util.List;
import java.util.Optional;
import org.springframework.data.repository.Repository;

/**
 * Accès aux lignes, toujours filtré par propriétaire (spécification §12) : ce dépôt n'expose volontairement aucune
 * méthode qui lirait les lignes de tout le monde ({@code findAll}, {@code findById}…).
 */
public interface RuleRepository extends Repository<Rule, Long> {

    Rule save(Rule rule);

    Optional<Rule> findByIdAndOwnerId(long id, long ownerId);

    Optional<Rule> findByOwnerIdAndBusinessId(long ownerId, String businessId);

    List<Rule> findAllByOwnerIdOrderByBusinessIdAsc(long ownerId);

    List<Rule> findAllByOwnerIdAndBusinessIdIn(long ownerId, Iterable<String> businessIds);

    long countByOwnerId(long ownerId);

    /** Envoie les écritures en attente à la base (pour détecter une violation d'unicité dans la transaction). */
    void flush();
}
