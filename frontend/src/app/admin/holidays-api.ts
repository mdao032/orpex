import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';

export interface Holiday {
  /** Date ISO (AAAA-MM-JJ). */
  day: string;
  label: string;
}

export interface YearAdded {
  added: Holiday[];
  alreadyPresent: number;
}

/** API des jours fériés (/api/admin/holidays). */
@Injectable({ providedIn: 'root' })
export class HolidaysApi {
  private readonly http = inject(HttpClient);
  private readonly base = '/api/admin/holidays';

  list(): Promise<Holiday[]> {
    return firstValueFrom(this.http.get<Holiday[]>(this.base));
  }

  add(day: string, label: string): Promise<Holiday> {
    return firstValueFrom(this.http.post<Holiday>(this.base, { day, label }));
  }

  /** Ajoute les 11 jours fériés de France métropolitaine de l'année ; les dates déjà présentes sont ignorées. */
  addFrench(year: number): Promise<YearAdded> {
    return firstValueFrom(this.http.post<YearAdded>(`${this.base}/french/${year}`, null));
  }

  remove(day: string): Promise<void> {
    return firstValueFrom(this.http.delete<void>(`${this.base}/${day}`));
  }
}
