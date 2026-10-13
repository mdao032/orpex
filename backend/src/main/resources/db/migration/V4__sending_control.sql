-- Pause générale des envois, décidée par un administrateur (spécification §11) : une seule ligne pour l'installation.
CREATE TABLE sending_control (
    id         SMALLINT    PRIMARY KEY CHECK (id = 1),
    paused     BOOLEAN     NOT NULL DEFAULT FALSE,
    changed_at TIMESTAMPTZ,
    changed_by BIGINT      REFERENCES users (id)
);

INSERT INTO sending_control (id) VALUES (1);
