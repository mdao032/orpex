import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';

export interface Holiday {
  /** Date ISO (AAAA-MM-JJ). */
  day: string;
  label: string;
}

export interface Country {
  code: string;
  label: string;
  /** Fêtes légales non calculées (calendrier lunaire), à saisir à la main une fois leur date annoncée. */
  manualDays: string[];
}

export interface YearAdded {
  added: Holiday[];
  alreadyPresent: number;
  manualDays: string[];
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

  /** Pays dont les jours fériés peuvent être ajoutés automatiquement. */
  countries(): Promise<Country[]> {
    return firstValueFrom(this.http.get<Country[]>(`${this.base}/countries`));
  }

  /** Ajoute les jours fériés calculables du pays pour l'année ; les dates déjà présentes sont ignorées. */
  addNational(country: string, year: number): Promise<YearAdded> {
    return firstValueFrom(this.http.post<YearAdded>(`${this.base}/national/${country}/${year}`, null));
  }

  remove(day: string): Promise<void> {
    return firstValueFrom(this.http.delete<void>(`${this.base}/${day}`));
  }
}
