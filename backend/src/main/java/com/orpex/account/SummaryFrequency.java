package com.orpex.account;

/** Fréquence du résumé des relances, choisie par chaque utilisateur. */
public enum SummaryFrequency {
    /** Chaque jour ouvré, à la fin de la journée de travail (spécification §11). */
    DAILY,
    /** Chaque semaine, le dernier jour ouvré (vendredi en général), à la fin de la journée de travail. */
    WEEKLY_FRIDAY,
    /** Chaque semaine, le premier jour ouvré (lundi en général), au début de la journée de travail. */
    WEEKLY_MONDAY
}
