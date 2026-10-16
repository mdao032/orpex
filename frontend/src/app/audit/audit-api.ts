import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';

/** Une ligne du journal ; `actor` nul : action du système (envois automatiques). */
export interface AuditView {
  id: number;
  occurredAt: string;
  actor: string | null;
  event: string;
  eventLabel: string;
  objectType: string | null;
  objectId: string | null;
  before: string | null;
  after: string | null;
}

/** Période (dates AAAA-MM-JJ incluses) ; vide = tout. */
export interface Period {
  from: string;
  to: string;
}

/** Journal d'audit : le sien (`/api/audit`) ou, pour l'administrateur, tout (`/api/admin/audit`). */
@Injectable({ providedIn: 'root' })
export class AuditApi {
  private readonly http = inject(HttpClient);

  list(all: boolean, period: Period): Promise<AuditView[]> {
    return firstValueFrom(this.http.get<AuditView[]>(this.base(all), { params: params(period) }));
  }

  /** Adresse de l'export CSV (lien direct : la session suit par cookie). */
  exportUrl(all: boolean, period: Period): string {
    const query = params(period).toString();
    return `${this.base(all)}/export${query ? '?' + query : ''}`;
  }

  private base(all: boolean): string {
    return all ? '/api/admin/audit' : '/api/audit';
  }
}

function params(period: Period): HttpParams {
  let p = new HttpParams();
  if (period.from) p = p.set('from', period.from);
  if (period.to) p = p.set('to', period.to);
  return p;
}
