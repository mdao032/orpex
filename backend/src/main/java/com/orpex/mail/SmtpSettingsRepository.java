package com.orpex.mail;

import java.util.Optional;
import org.springframework.data.repository.Repository;

interface SmtpSettingsRepository extends Repository<SmtpSettings, Short> {

    Optional<SmtpSettings> findById(Short id);

    SmtpSettings save(SmtpSettings settings);
}
