-- Erreur du dernier contrôle de santé du relais SMTP (spécification §10), affichée à l'administrateur.
ALTER TABLE smtp_settings ADD COLUMN health_detail TEXT;
