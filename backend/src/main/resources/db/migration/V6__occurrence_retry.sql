-- Nouvelle tentative après un échec temporaire (spécification §10 : jusqu'à 3, espacées). Null : pas de tentative en
-- attente, l'envoi part à son heure prévue (scheduled_at).
ALTER TABLE occurrences ADD COLUMN next_attempt_at TIMESTAMPTZ;
