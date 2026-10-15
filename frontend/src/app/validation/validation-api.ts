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
  /** Faux : heure limite passée, l'envoi ne partira pas. */
  open: boolean;
  alreadySent: number;
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

  /** {@code validate} partiront à leur heure ; {@code reject} (décochés) ne partiront pas. */
  decide(validate: number[], reject: number[]): Promise<Decision> {
    return firstValueFrom(this.http.post<Decision>('/api/validation', { validate, reject }));
  }
}
