-- Activation explicite (bouton) : tant qu'une ligne n'est pas activée, aucun envoi n'existe pour elle.
ALTER TABLE rules ADD COLUMN activated_at TIMESTAMPTZ;
