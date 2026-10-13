package com.orpex.rule;

import java.util.EnumSet;
import java.util.Set;

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
    MISSED;

    /** Envois qui n'ont pas encore commencé à partir : ce sont eux qu'une clôture ou une pause concerne. */
    public static final Set<OccurrenceState> NOT_STARTED = EnumSet.of(PLANNED, AWAITING_VALIDATION, VALIDATED);
}
