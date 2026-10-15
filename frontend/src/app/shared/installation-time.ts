import { Pipe, PipeTransform, inject } from '@angular/core';
import { Auth } from '../auth/auth';

/**
 * Formate un instant (date et heure ISO) dans le fuseau d'un lieu, par exemple « 15/10/2026 à 09:00 ».
 * Motifs reconnus : yyyy, MM, dd, HH, mm (le reste est recopié tel quel).
 */
export function formatInZone(iso: string | null | undefined, pattern: string, timeZone?: string): string {
  if (!iso) {
    return '';
  }
  const parts = new Intl.DateTimeFormat('fr-FR', {
    timeZone,
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    hourCycle: 'h23',
  }).formatToParts(new Date(iso));
  const part = (type: Intl.DateTimeFormatPartTypes) => parts.find((p) => p.type === type)?.value ?? '';
  const values: Record<string, string> = {
    yyyy: part('year'),
    MM: part('month'),
    dd: part('day'),
    HH: part('hour'),
    mm: part('minute'),
  };
  return pattern.replace(/yyyy|MM|dd|HH|mm/g, (token) => values[token]);
}

/**
 * Affiche un instant dans le fuseau de l'installation (celui des envois, `Africa/Bamako` pour le pilote), et non
 * dans celui du navigateur : une relance prévue à 09:00 s'affiche 09:00 partout.
 * Exemple : {{ envoi.scheduledAt | heure: 'dd/MM/yyyy à HH:mm' }}
 */
@Pipe({ name: 'heure' })
export class InstallationTimePipe implements PipeTransform {
  private readonly auth = inject(Auth);

  transform(iso: string | null | undefined, pattern = 'dd/MM/yyyy à HH:mm'): string {
    return formatInZone(iso, pattern, this.auth.user()?.timezone);
  }
}
