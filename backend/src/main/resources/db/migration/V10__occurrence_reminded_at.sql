-- Récapitulatif de validation envoyé au propriétaire pour cet envoi (spécification §7) : jamais deux fois.
ALTER TABLE occurrences ADD COLUMN reminded_at TIMESTAMPTZ;
