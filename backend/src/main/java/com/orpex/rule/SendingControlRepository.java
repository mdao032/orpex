package com.orpex.rule;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.repository.Repository;

interface SendingControlRepository extends Repository<SendingControl, Short> {

    Optional<SendingControl> findById(Short id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<SendingControl> findForUpdateById(Short id);
}
