-- Fréquence du résumé choisie par chaque utilisateur : chaque jour ouvré (spécification §11, par défaut), ou chaque
-- semaine, le vendredi à la fin de la journée de travail ou le lundi à son début.
ALTER TABLE users ADD COLUMN summary_frequency VARCHAR(20) NOT NULL DEFAULT 'DAILY'
    CHECK (summary_frequency IN ('DAILY', 'WEEKLY_FRIDAY', 'WEEKLY_MONDAY'));
