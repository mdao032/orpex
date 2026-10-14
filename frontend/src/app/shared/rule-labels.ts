import { daysRange } from './schedule-label';

export type OccurrenceState =
  | 'PLANNED'
  | 'AWAITING_VALIDATION'
  | 'VALIDATED'
  | 'SENDING'
  | 'SENT'
  | 'FAILED'
  | 'TO_CHECK'
  | 'NOT_VALIDATED'
  | 'CANCELLED'
  | 'MISSED';

/** États d'un envoi, en français, dans l'ordre où ils sont présentés. */
export const OCCURRENCE_STATES: Record<OccurrenceState, string> = {
  PLANNED: 'Prévu',
  AWAITING_VALIDATION: 'En attente de validation',
  VALIDATED: 'Validé',
  SENDING: "En cours d'envoi",
  SENT: 'Envoyé',
  FAILED: 'Échec',
  TO_CHECK: 'À vérifier',
  NOT_VALIDATED: 'Non validé',
  CANCELLED: 'Annulé',
  MISSED: 'Manqué',
};

/** Statut d'une relance pour l'utilisateur. */
export function ruleStatusLabel(r: { status: 'ACTIVE' | 'CLOSED'; activatedAt: string | null; paused: boolean }): string {
  if (r.status === 'CLOSED') {
    return 'Clos';
  }
  if (!r.activatedAt) {
    return 'Non activée';
  }
  return r.paused ? 'En pause' : 'Active';
}

/** « À l'activation : 5 envoi(s) prévu(s) du … au … » pour une relance non activée. */
export function forecastLabel(f: { planned: number; first: string | null; last: string | null; missedToday: number }): string {
  if (f.planned === 0) {
    return "À l'activation : aucun envoi à venir.";
  }
  return `À l'activation : ${f.planned} envoi(s) prévu(s) ${daysRange(f.first, f.last)}`
    + (f.missedToday > 0 ? " (celui d'aujourd'hui est déjà passé)." : '.');
}

/** Message après activation : « P-7 activée : 5 envoi(s) du … au … ». */
export function activationMessage(r: { businessId: string; planned: number; missed: number; first: string | null;
  last: string | null }): string {
  const envois = r.planned === 0 ? 'aucun envoi à venir' : `${r.planned} envoi(s) ${daysRange(r.first, r.last)}`;
  return `${r.businessId} activée : ${envois}` + (r.missed > 0 ? ' (envoi du jour manqué : heure passée).' : '.');
}

/** Message après pause ou reprise. */
export function pauseMessage(businessId: string, r: { paused: boolean; missed: number }): string {
  if (r.paused) {
    return `${businessId} en pause : ses envois ne partiront pas avant la reprise.`;
  }
  return `${businessId} reprise` + (r.missed > 0 ? ` : ${r.missed} envoi(s) manqué(s) pendant la pause.` : '.');
}

export function closeQuestion(businessId: string): string {
  return `Clore le dossier ${businessId} ? Ses envois à venir seront annulés ; cette action est définitive.`;
}

export function closeMessage(businessId: string, cancelled: number): string {
  return `Dossier ${businessId} clos : ${cancelled} envoi(s) à venir annulé(s).`;
}
