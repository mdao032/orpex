-- Mot de passe provisoire (saisi par un administrateur ou lu dans la configuration) : à changer à la connexion suivante.
ALTER TABLE users ADD COLUMN must_change_password BOOLEAN NOT NULL DEFAULT FALSE;
