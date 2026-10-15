-- Champs d'une relance modifiés à l'écran depuis le dernier import (JSON : colonne -> valeur saisie),
-- pour signaler un conflit si un ré-import propose une autre valeur (spécification §9).
ALTER TABLE rules ADD COLUMN edited_in_app text NOT NULL DEFAULT '{}';
