-- Certificat de l'autorité interne de la banque (PEM), pour les connexions chiffrées vers le relais (spécification §10).
ALTER TABLE smtp_settings ADD COLUMN ca_certificate TEXT;
