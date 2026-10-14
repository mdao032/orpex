import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { OccurrenceState } from '../shared/rule-labels';
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

export interface OccurrenceView {
  id: number;
  scheduledDate: string;
  scheduledAt: string;
  state: OccurrenceState;
  validatedAt: string | null;
  sentAt: string | null;
  attempts: number;
  lastError: string | null;
  /** Prochaine tentative après un échec temporaire. */
  nextAttemptAt: string | null;
}

/** Détail d'une relance : configuration, message, envois et leur nombre par état. */
export interface RuleDetail {
  rule: RuleView;
  body: string;
  sends: OccurrenceView[];
  countsByState: Partial<Record<OccurrenceState, number>>;
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

  detail(id: number): Promise<RuleDetail> {
    return firstValueFrom(this.http.get<RuleDetail>(`/api/rules/${id}`));
  }

  /** Envoi « à vérifier » (arrêt pendant l'envoi) : l'utilisateur déclare s'il est parti. Jamais renvoyé. */
  resolveToCheck(ruleId: number, sendId: number, sent: boolean): Promise<OccurrenceView> {
    return firstValueFrom(
      this.http.post<OccurrenceView>(`/api/rules/${ruleId}/sends/${sendId}/resolve`, { sent }),
    );
  }

  /** Crée les envois de la relance. */
  activate(id: number): Promise<ActivationResult> {
    return firstValueFrom(this.http.post<ActivationResult>(`/api/rules/${id}/activate`, null));
  }

  /** Dossier réglé : statut clos, envois futurs non partis annulés. */
  close(id: number): Promise<{ ruleId: number; businessId: string; cancelled: number }> {
    return firstValueFrom(
      this.http.post<{ ruleId: number; businessId: string; cancelled: number }>(`/api/rules/${id}/close`, null),
    );
  }

  /** Pause (true) ou reprise (false) ; à la reprise, {@code missed} envois dont l'heure est passée sont manqués. */
  setPaused(id: number, paused: boolean): Promise<{ businessId: string; paused: boolean; missed: number }> {
    return firstValueFrom(this.http.post<{ businessId: string; paused: boolean; missed: number }>(
      `/api/rules/${id}/${paused ? 'pause' : 'resume'}`, null));
  }

  /** Active toutes les relances non encore activées et non closes. */
  activateAll(): Promise<ActivationResult[]> {
    return firstValueFrom(this.http.post<ActivationResult[]>('/api/rules/activate-all', null));
  }
}
