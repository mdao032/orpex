package com.orpex.rule;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Écart entre les envois existants d'une ligne et ceux que sa configuration demande maintenant (jours, heure,
 * validation). Ne touche que l'avenir : un envoi parti, manqué, en échec, ou dont l'heure est passée, ne change jamais.
 * Sert à l'aperçu (comptes, rien n'est écrit) et à l'application (mêmes changements).
 *
 * @param toCancel envois à venir non commencés sur un jour qui n'est plus un jour d'envoi
 * @param toRevive envois annulés d'un jour qui redevient un jour d'envoi (un envoi par ligne et par jour)
 * @param toMove   envois à venir non commencés dont l'heure ou l'état d'attente change (heure d'envoi, validation)
 * @param toCreate jours d'envoi à venir sans envoi
 */
public record SendsDiff(List<Occurrence> toCancel, List<Occurrence> toRevive, List<Occurrence> toMove,
                        List<LocalDate> toCreate) {

    /**
     * @param existing   tous les envois de la ligne
     * @param wantedDays jours d'envoi voulus (calcul du cœur métier)
     * @param at         heure d'envoi (instant) d'un jour, avec la nouvelle heure d'envoi
     * @param upcoming   état d'attente voulu : {@code PLANNED}, ou {@code AWAITING_VALIDATION} avec validation quotidienne
     * @param now        maintenant : seuls les envois dont l'heure est encore à venir sont concernés
     */
    public static SendsDiff compute(Collection<Occurrence> existing, Collection<LocalDate> wantedDays,
            Function<LocalDate, Instant> at, OccurrenceState upcoming, Instant now) {
        Set<LocalDate> wanted = wantedDays.stream().filter(day -> at.apply(day).isAfter(now))
                .collect(Collectors.toSet());
        Map<LocalDate, Occurrence> byDay = existing.stream()
                .collect(Collectors.toMap(Occurrence::getScheduledDate, Function.identity()));
        List<Occurrence> toCancel = new ArrayList<>();
        List<Occurrence> toMove = new ArrayList<>();
        for (Occurrence occurrence : existing) {
            if (!OccurrenceState.NOT_STARTED.contains(occurrence.getState())
                    || !occurrence.getScheduledAt().isAfter(now)) {
                continue;
            }
            if (!wanted.contains(occurrence.getScheduledDate())) {
                toCancel.add(occurrence);
            } else if (!occurrence.getScheduledAt().equals(at.apply(occurrence.getScheduledDate()))
                    || !occurrence.getState().equals(waitingState(occurrence.getState(), upcoming))) {
                toMove.add(occurrence);
            }
        }
        List<Occurrence> toRevive = new ArrayList<>();
        List<LocalDate> toCreate = new ArrayList<>();
        for (LocalDate day : wanted.stream().sorted().toList()) {
            Occurrence occurrence = byDay.get(day);
            if (occurrence == null) {
                toCreate.add(day);
            } else if (occurrence.getState() == OccurrenceState.CANCELLED) {
                toRevive.add(occurrence);
            }
        }
        return new SendsDiff(toCancel, toRevive, toMove, toCreate);
    }

    /**
     * État d'attente d'un envoi non commencé selon la validation voulue : un envoi déjà validé le reste tant que la
     * validation est demandée ; sans validation, tout envoi en attente devient « prévu ».
     */
    static OccurrenceState waitingState(OccurrenceState current, OccurrenceState upcoming) {
        if (upcoming == OccurrenceState.AWAITING_VALIDATION && current == OccurrenceState.VALIDATED) {
            return OccurrenceState.VALIDATED;
        }
        return upcoming;
    }

    public int cancelled() {
        return toCancel.size();
    }

    public int added() {
        return toRevive.size() + toCreate.size();
    }

    public int moved() {
        return toMove.size();
    }

    public boolean isEmpty() {
        return toCancel.isEmpty() && toRevive.isEmpty() && toMove.isEmpty() && toCreate.isEmpty();
    }
}
