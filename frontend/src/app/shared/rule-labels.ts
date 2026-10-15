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

/** Nature du dernier problème d'un envoi (ou de la décision après vérification). */
export type SendIssue =
  | 'RELAY_UNREACHABLE'
  | 'RELAY_TEMPORARY'
  | 'RECIPIENT_REFUSED'
  | 'RELAY_REFUSED'
  | 'AUTH_REFUSED'
  | 'CONFIGURATION'
  | 'MESSAGE_INCOMPLETE'
  | 'INTERRUPTED'
  | 'CONFIRMED_SENT'
  | 'CONFIRMED_NOT_SENT';

/** Message pour l'utilisateur quand un envoi a définitivement échoué (ou qu'un envoi de test n'est pas parti). */
const FINAL: Record<SendIssue, string> = {
  RELAY_UNREACHABLE: 'Le serveur de messagerie ne répond pas. Prévenez l’administrateur.',
  RELAY_TEMPORARY: 'Le serveur de messagerie a refusé l’envoi. Prévenez l’administrateur.',
  RECIPIENT_REFUSED: 'L’adresse du destinataire a été refusée. Vérifiez-la dans votre fichier.',
  RELAY_REFUSED: 'Le serveur de messagerie a refusé ce message. Prévenez l’administrateur.',
  AUTH_REFUSED: 'Le serveur de messagerie refuse la connexion de l’application. Prévenez l’administrateur.',
  CONFIGURATION: 'La configuration de la messagerie est inutilisable. Prévenez l’administrateur.',
  MESSAGE_INCOMPLETE: 'Le message contient une variable sans valeur. Corrigez le fichier.',
  INTERRUPTED: 'L’application s’est arrêtée pendant l’envoi : impossible de savoir si le mail est parti. '
    + 'Vérifiez, puis indiquez-le ci-dessous.',
  CONFIRMED_SENT: 'Déclaré parti après vérification.',
  CONFIRMED_NOT_SENT: 'Déclaré non parti après vérification : il ne sera pas renvoyé.',
};

export interface SendProblem {
  /** Phrase claire, qui dit quoi faire. */
  text: string;
  /** erreur, avertissement (nouvel essai automatique) ou information. */
  level: 'erreur' | 'avertissement' | 'info';
  /** Détail technique à proposer (replié), pour l'administrateur. */
  technical: string | null;
}

/**
 * Message pour l'utilisateur à partir de la nature du problème d'un envoi ; {@code nextAttempt} : date et heure
 * déjà formatées du prochain essai automatique.
 */
export function sendProblem(
  send: { state: OccurrenceState; attempts: number; issue: SendIssue | null; lastError: string | null },
  maxAttempts: number,
  nextAttempt: string | null,
): SendProblem | null {
  const { state, attempts, issue, lastError } = send;
  if (!issue) {
    return lastError ? { text: lastError, level: 'erreur', technical: null } : null;
  }
  if (issue === 'CONFIRMED_SENT' || issue === 'CONFIRMED_NOT_SENT') {
    return { text: FINAL[issue], level: 'info', technical: null };
  }
  if (nextAttempt && state !== 'FAILED') {
    const cause = issue === 'RELAY_TEMPORARY'
      ? 'Le serveur de messagerie a refusé l’envoi pour le moment.'
      : 'Le serveur de messagerie ne répond pas.';
    return {
      text: `${cause} Nouvel essai automatique le ${nextAttempt} (essai ${attempts + 1} sur ${maxAttempts}).`,
      level: 'avertissement',
      technical: lastError,
    };
  }
  let text = FINAL[issue];
  if (issue === 'MESSAGE_INCOMPLETE') {
    const variable = lastError?.match(/\{\{\s*([a-z0-9_]+)\s*}}/)?.[1];
    text = variable ? `Le message contient une variable sans valeur ({{${variable}}}). Corrigez le fichier.` : text;
  } else if ((issue === 'RELAY_UNREACHABLE' || issue === 'RELAY_TEMPORARY') && attempts > 1) {
    text = issue === 'RELAY_UNREACHABLE'
      ? `Le serveur de messagerie ne répond pas, et les ${attempts} essais ont échoué. Prévenez l’administrateur.`
      : `Le serveur de messagerie a refusé l’envoi à chaque essai (${attempts}). Prévenez l’administrateur.`;
  }
  return { text, level: 'erreur', technical: lastError };
}

/** Message d'un envoi de test qui n'est pas parti. */
export function testSendProblem(issue: SendIssue | null, detail: string | null): string {
  return issue ? FINAL[issue] : `L'envoi de test n'est pas parti : ${detail ?? 'erreur inconnue'}.`;
}

/** Effet d'une modification sur les envois à venir, par exemple « 4 déplacé(s), 1 annulé(s) » ; vide si aucun. */
export function sendImpactLabel(added: number, moved: number, cancelled: number): string {
  const parts: string[] = [];
  if (moved > 0) parts.push(`${moved} déplacé(s)`);
  if (cancelled > 0) parts.push(`${cancelled} annulé(s)`);
  if (added > 0) parts.push(`${added} ajouté(s)`);
  return parts.join(', ');
}
