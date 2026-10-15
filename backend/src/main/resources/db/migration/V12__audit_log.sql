-- Journal d'audit (spécification §11) : propriétaire des lignes concernées, pour que chacun consulte le journal de
-- ses relances, et index de consultation. La table reste en ajout seul (trigger audit_log_read_only, V1).
ALTER TABLE audit_log ADD COLUMN owner_id BIGINT REFERENCES users (id);

CREATE INDEX audit_log_owner_idx ON audit_log (owner_id, occurred_at DESC);
CREATE INDEX audit_log_occurred_idx ON audit_log (occurred_at DESC);

-- Chaque changement d'état d'un envoi est journalisé par la base elle-même, y compris les mises à jour groupées du
-- worker : ligne, destinataire, date prévue, date réelle, état, erreur. Le passage transitoire « en cours d'envoi »
-- n'est pas journalisé (l'état suivant l'est, avec l'ancien état « SENDING »).
CREATE FUNCTION audit_occurrence_state() RETURNS trigger AS $$
BEGIN
    INSERT INTO audit_log (actor_id, event_type, object_type, object_id, old_value, new_value, owner_id)
    SELECT CASE WHEN NEW.state IN ('VALIDATED', 'NOT_VALIDATED') THEN NEW.validated_by END,
           'SEND_STATE_CHANGED', 'OCCURRENCE', NEW.id::text, OLD.state,
           jsonb_build_object('relance', r.business_id, 'destinataire', r.recipient_email,
                              'jour_prevu', NEW.scheduled_date, 'heure_prevue', NEW.scheduled_at,
                              'envoye_le', NEW.sent_at, 'etat', NEW.state, 'tentatives', NEW.attempts,
                              'erreur', NEW.last_error)::text,
           r.owner_id
    FROM rules r
    WHERE r.id = NEW.rule_id;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER occurrences_audit_state
    AFTER UPDATE OF state ON occurrences
    FOR EACH ROW
    WHEN (OLD.state IS DISTINCT FROM NEW.state AND NEW.state <> 'SENDING')
    EXECUTE FUNCTION audit_occurrence_state();
