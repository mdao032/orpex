-- Notifications (spécification §11).

-- Journée de travail réglée par l'administrateur : le résumé quotidien part à l'heure de fin.
CREATE TABLE notification_settings (
    id            SMALLINT PRIMARY KEY CHECK (id = 1),
    workday_start TIME        NOT NULL,
    workday_end   TIME        NOT NULL,
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by    BIGINT REFERENCES users (id),
    CHECK (workday_start < workday_end)
);

INSERT INTO notification_settings (id, workday_start, workday_end) VALUES (1, '08:00', '18:00');

-- Un résumé quotidien au plus par propriétaire et par jour.
CREATE TABLE daily_summaries (
    owner_id BIGINT      NOT NULL REFERENCES users (id),
    day      DATE        NOT NULL,
    sent_at  TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (owner_id, day)
);

-- Alerte d'échec envoyée pour cet envoi (échec ou à vérifier). Les échecs antérieurs ne sont pas signalés.
ALTER TABLE occurrences ADD COLUMN alerted_at TIMESTAMPTZ;
UPDATE occurrences SET alerted_at = now() WHERE state IN ('FAILED', 'TO_CHECK');
