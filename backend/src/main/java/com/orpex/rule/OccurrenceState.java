package com.orpex.rule;

/** États d'un envoi (spécification §8 ; {@code VALIDATED} ajouté : validé, en attente de son heure). */
public enum OccurrenceState {
    /** Prévu, part seul à son heure (ligne sans validation quotidienne). */
    PLANNED,
    /** Prévu, attend la validation du jour de son propriétaire : sans validation, rien ne part. */
    AWAITING_VALIDATION,
    VALIDATED,
    SENDING,
    SENT,
    FAILED,
    /** Arrêt entre l'acceptation par le serveur de mail et l'écriture du statut : jamais de renvoi automatique. */
    TO_CHECK,
    NOT_VALIDATED,
    CANCELLED,
    /** Heure passée sans envoi (activation tardive, arrêt du service) : jamais rattrapé. */
    MISSED
}
