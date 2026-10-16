package com.orpex.notification;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

interface NotificationSettingsRepository extends Repository<NotificationSettings, Short> {

    Optional<NotificationSettings> findById(Short id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from NotificationSettings s where s.id = :id")
    Optional<NotificationSettings> findForUpdateById(@Param("id") Short id);

    /** Résumé quotidien : réserve (propriétaire, jour) ; 0 si déjà envoyé. */
    @Modifying
    @Query(nativeQuery = true, value = """
            insert into daily_summaries (owner_id, day, sent_at) values (:ownerId, :day, :now)
            on conflict do nothing""")
    int reserveDailySummary(@Param("ownerId") long ownerId, @Param("day") LocalDate day, @Param("now") Instant now);

    @Query(nativeQuery = true, value = "select count(*) > 0 from daily_summaries where owner_id = :ownerId and day = :day")
    boolean dailySummarySent(@Param("ownerId") long ownerId, @Param("day") LocalDate day);
}
