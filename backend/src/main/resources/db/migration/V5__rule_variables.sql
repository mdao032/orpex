-- Valeurs des variables {{colonne}} utilisées dans l'objet et le contenu, relevées à l'import (objet JSON) :
-- une colonne libre du fichier (ex. {{montant}}) doit pouvoir être remplacée au moment de l'envoi.
ALTER TABLE rules ADD COLUMN variables TEXT NOT NULL DEFAULT '{}';
