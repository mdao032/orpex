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
  updatedAt: string;
}

/** Relances de l'utilisateur connecté (/api/rules). */
@Injectable({ providedIn: 'root' })
export class RulesApi {
  private readonly http = inject(HttpClient);

  list(): Promise<RuleView[]> {
    return firstValueFrom(this.http.get<RuleView[]>('/api/rules'));
  }
}
