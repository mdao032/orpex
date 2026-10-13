import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { Schedule } from '../shared/schedule-label';

export interface RuleView extends Schedule {
  id: number;
  businessId: string;
  recipientEmail: string;
  firstName: string | null;
  lastName: string | null;
  caseReference: string | null;
  subject: string;
  dailyValidation: boolean;
  status: 'ACTIVE' | 'CLOSED';
  paused: boolean;
  /** Null tant que la relance n'est pas activée : aucun envoi n'existe alors. */
  activatedAt: string | null;
  updatedAt: string;
  /** Envois que l'activation créerait maintenant ; seulement pour une relance non activée et non close. */
  forecast: { planned: number; first: string | null; last: string | null; missedToday: number } | null;
}

export interface ActivationResult {
  ruleId: number;
  businessId: string;
  planned: number;
  missed: number;
  first: string | null;
  last: string | null;
}

/** Relances de l'utilisateur connecté (/api/rules). */
@Injectable({ providedIn: 'root' })
export class RulesApi {
  private readonly http = inject(HttpClient);

  list(): Promise<RuleView[]> {
    return firstValueFrom(this.http.get<RuleView[]>('/api/rules'));
  }

  /** Crée les envois de la relance. */
  activate(id: number): Promise<ActivationResult> {
    return firstValueFrom(this.http.post<ActivationResult>(`/api/rules/${id}/activate`, null));
  }

  /** Active toutes les relances non encore activées et non closes. */
  activateAll(): Promise<ActivationResult[]> {
    return firstValueFrom(this.http.post<ActivationResult[]>('/api/rules/activate-all', null));
  }
}
