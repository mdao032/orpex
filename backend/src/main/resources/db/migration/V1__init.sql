-- ORPEX : schéma initial (spécification, section 12).
-- La configuration d'une ligne (rules), l'état de ses envois (occurrences) et le journal (audit_log)
-- sont dans des tables séparées : un import ne peut jamais écraser l'état d'exécution.

CREATE TABLE users (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    email           VARCHAR(320) NOT NULL UNIQUE,
    password_hash   VARCHAR(255) NOT NULL,
    role            VARCHAR(10)  NOT NULL CHECK (role IN ('USER', 'ADMIN')),
    enabled         BOOLEAN      NOT NULL DEFAULT TRUE,
    failed_attempts INTEGER      NOT NULL DEFAULT 0,
    locked_until    TIMESTAMPTZ,
    last_login_at   TIMESTAMPTZ,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE rules (
    id                 BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    owner_id           BIGINT       NOT NULL REFERENCES users (id),
    business_id        VARCHAR(100) NOT NULL,
    recipient_email    VARCHAR(320) NOT NULL,
    first_name         VARCHAR(200),
    last_name          VARCHAR(200),
    case_reference     VARCHAR(200),
    subject            VARCHAR(500) NOT NULL,
    body               TEXT         NOT NULL,
    send_mode          VARCHAR(20)  NOT NULL CHECK (send_mode IN ('PONCTUEL', 'AVANT_ECHEANCE', 'PERIODE')),
    send_date          DATE,
    due_date           DATE,
    days_before        INTEGER CHECK (days_before >= 0),
    start_date         DATE,
    end_date           DATE,
    send_time          TIME         NOT NULL,
    business_days_only BOOLEAN      NOT NULL,
    daily_validation   BOOLEAN      NOT NULL,
    status             VARCHAR(10)  NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'CLOSED')),
    paused             BOOLEAN      NOT NULL DEFAULT FALSE,
    config_hash        CHAR(64)     NOT NULL,
    created_by         BIGINT       NOT NULL REFERENCES users (id),
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    -- L'identifiant du fichier est unique par propriétaire (clé du ré-import).
    CONSTRAINT rules_business_id_unique UNIQUE (owner_id, business_id),
    -- Chaque mode exige ses propres dates.
    CONSTRAINT rules_mode_fields CHECK (
           (send_mode = 'PONCTUEL' AND send_date IS NOT NULL)
        OR (send_mode = 'AVANT_ECHEANCE' AND due_date IS NOT NULL AND days_before IS NOT NULL)
        OR (send_mode = 'PERIODE' AND start_date IS NOT NULL AND end_date IS NOT NULL AND end_date >= start_date)
    )
);

-- Un envoi planifié = une ligne, un jour. VALIDATED = validé, en attente de l'heure d'envoi.
CREATE TABLE occurrences (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    rule_id        BIGINT      NOT NULL REFERENCES rules (id),
    scheduled_date DATE        NOT NULL,
    scheduled_at   TIMESTAMPTZ NOT NULL,
    state          VARCHAR(20) NOT NULL CHECK (state IN (
                       'PLANNED', 'AWAITING_VALIDATION', 'VALIDATED', 'SENDING', 'SENT',
                       'FAILED', 'TO_CHECK', 'NOT_VALIDATED', 'CANCELLED', 'MISSED')),
    created_by     BIGINT      NOT NULL REFERENCES users (id),
    validated_by   BIGINT REFERENCES users (id),
    validated_at   TIMESTAMPTZ,
    sent_at        TIMESTAMPTZ,
    attempts       INTEGER     NOT NULL DEFAULT 0,
    last_error     TEXT,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- Garantit qu'un envoi ne peut exister, donc partir, qu'une seule fois par ligne et par jour.
    CONSTRAINT occurrences_rule_day_unique UNIQUE (rule_id, scheduled_date)
);

-- Le worker cherche les envois dus parmi les états encore actifs.
CREATE INDEX occurrences_due_idx ON occurrences (scheduled_at)
    WHERE state IN ('PLANNED', 'AWAITING_VALIDATION', 'VALIDATED');

CREATE TABLE imports (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id         BIGINT       NOT NULL REFERENCES users (id),
    file_name       VARCHAR(500) NOT NULL,
    created_count   INTEGER      NOT NULL DEFAULT 0,
    updated_count   INTEGER      NOT NULL DEFAULT 0,
    unchanged_count INTEGER      NOT NULL DEFAULT 0,
    rejected_count  INTEGER      NOT NULL DEFAULT 0,
    summary         TEXT,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE audit_log (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    actor_id    BIGINT REFERENCES users (id),
    event_type  VARCHAR(60) NOT NULL,
    object_type VARCHAR(40),
    object_id   VARCHAR(100),
    old_value   TEXT,
    new_value   TEXT
);

-- Une seule ligne de configuration SMTP pour toute l'installation.
CREATE TABLE smtp_settings (
    id                 SMALLINT PRIMARY KEY CHECK (id = 1),
    host               VARCHAR(255) NOT NULL,
    port               INTEGER      NOT NULL,
    encryption         VARCHAR(10)  NOT NULL CHECK (encryption IN ('NONE', 'STARTTLS', 'TLS')),
    username           VARCHAR(255),
    password_encrypted BYTEA,
    from_address       VARCHAR(320) NOT NULL,
    from_name          VARCHAR(200),
    health_status      VARCHAR(20)  NOT NULL DEFAULT 'UNKNOWN',
    health_checked_at  TIMESTAMPTZ,
    updated_at         TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE calendar_days (
    day   DATE PRIMARY KEY,
    label VARCHAR(200) NOT NULL
);

-- Aucune suppression physique des envois, aucune modification ni suppression du journal.
CREATE FUNCTION forbid_modification() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'Opération interdite sur la table % : conservation obligatoire', TG_TABLE_NAME;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER audit_log_read_only
    BEFORE UPDATE OR DELETE ON audit_log
    FOR EACH ROW EXECUTE FUNCTION forbid_modification();

CREATE TRIGGER occurrences_no_delete
    BEFORE DELETE ON occurrences
    FOR EACH ROW EXECUTE FUNCTION forbid_modification();
