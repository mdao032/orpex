-- Nature du problème d'un envoi (serveur injoignable, adresse refusée, envoi interrompu…), pour afficher un message
-- clair à l'utilisateur ; last_error garde le détail technique pour l'administrateur.
ALTER TABLE occurrences ADD COLUMN error_kind VARCHAR(30);
