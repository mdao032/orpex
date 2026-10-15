package com.orpex.audit;

import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.repository.Repository;

/** Journal en ajout seul : ni mise à jour ni suppression exposées (et refusées par la base). */
public interface AuditRepository extends Repository<AuditEntry, Long> {

    AuditEntry save(AuditEntry entry);

    /** Journal des relances d'un utilisateur et de ses propres actions. */
    List<AuditEntry> findByOwnerIdOrActorIdOrderByOccurredAtDescIdDesc(Long ownerId, Long actorId, Pageable page);

    /** Administrateur : tout le journal. */
    List<AuditEntry> findAllByOrderByOccurredAtDescIdDesc(Pageable page);
}
