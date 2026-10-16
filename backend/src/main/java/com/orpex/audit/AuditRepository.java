package com.orpex.audit;

import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/** Journal en ajout seul : ni mise à jour ni suppression exposées (et refusées par la base). */
public interface AuditRepository extends Repository<AuditEntry, Long> {

    AuditEntry save(AuditEntry entry);

    /** Journal des relances d'un utilisateur et de ses propres actions. */
    List<AuditEntry> findByOwnerIdOrActorIdOrderByOccurredAtDescIdDesc(Long ownerId, Long actorId, Pageable page);

    /** Même chose avec l'email de l'auteur, sur une période : [entrée, email]. */
    @Query("""
            select a, u.email from AuditEntry a left join UserAccount u on u.id = a.actorId
            where (a.ownerId = :userId or a.actorId = :userId) and a.occurredAt >= :from and a.occurredAt < :to
            order by a.occurredAt desc, a.id desc""")
    List<Object[]> findForUser(@Param("userId") long userId, @Param("from") Instant from, @Param("to") Instant to,
            Pageable page);

    /** Administrateur : tout le journal sur une période, avec l'email de l'auteur. */
    @Query("""
            select a, u.email from AuditEntry a left join UserAccount u on u.id = a.actorId
            where a.occurredAt >= :from and a.occurredAt < :to
            order by a.occurredAt desc, a.id desc""")
    List<Object[]> findAll(@Param("from") Instant from, @Param("to") Instant to, Pageable page);
}
