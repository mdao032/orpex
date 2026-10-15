import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';

/** Une relance qui attend la validation du jour. */
export interface Pending {
  sendId: number;
  ruleId: number;
  businessId: string;
  caseReference: string | null;
  recipientEmail: string;
  subject: string;
  scheduledAt: string;
  /** Heure limite de validation. */
  cutoffAt: string;
  /** Faux : heure limite passée (plus de validation ni de changement d'avis possible). */
  open: boolean;
  alreadySent: number;
  /** Décision du jour : en attente, validée, ou décochée. */
  decision: 'PENDING' | 'VALIDATED' | 'UNCHECKED';
}

export interface Decision {
  validated: number;
  rejected: number;
  refused: { sendId: number; reason: string }[];
}

/** Validation du jour (/api/validation). */
@Injectable({ providedIn: 'root' })
export class ValidationApi {
  private readonly http = inject(HttpClient);

  today(): Promise<Pending[]> {
    return firstValueFrom(this.http.get<Pending[]>('/api/validation/today'));
  }

  /** Revenir sur sa décision du jour (avant l'heure limite) : l'envoi attend de nouveau sa validation. */
  reopen(sendId: number): Promise<Pending> {
    return firstValueFrom(this.http.post<Pending>('/api/validation/reopen', { sendId }));
  }

  /** {@code validate} partiront à leur heure ; {@code reject} (décochés) ne partiront pas. */
  decide(validate: number[], reject: number[]): Promise<Decision> {
    return firstValueFrom(this.http.post<Decision>('/api/validation', { validate, reject }));
  }
}
