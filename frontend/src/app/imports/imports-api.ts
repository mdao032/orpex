import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';

export type ImportAction = 'CREATE' | 'UPDATE' | 'UNCHANGED' | 'REJECTED';
export type SendMode = 'PONCTUEL' | 'AVANT_ECHEANCE' | 'PERIODE';

/** Valeurs d'une ligne telles que l'application les a comprises (dates ISO). */
export interface Interpreted {
  email: string;
  firstName: string;
  lastName: string;
  caseReference: string;
  subject: string;
  sendMode: SendMode;
  sendDate: string | null;
  dueDate: string | null;
  daysBefore: number | null;
  startDate: string | null;
  endDate: string | null;
  sendTime: string;
  businessDaysOnly: boolean;
  dailyValidation: boolean;
  status: 'ACTIVE' | 'CLOSED';
}

export interface RowPreview {
  rowNumber: number;
  id: string;
  action: ImportAction;
  errors: string[];
  warnings: string[];
  interpreted: Interpreted | null;
  sends: { planned: number; first: string | null; last: string | null; missedToday: number } | null;
}

export interface ImportPreview {
  fileName: string;
  missingColumns: string[];
  counts: {
    toCreate: number;
    toUpdate: number;
    unchanged: number;
    rejected: number;
    withWarnings: number;
    plannedSends: number;
  };
  rows: RowPreview[];
}

export interface ImportResult {
  importId: number;
  fileName: string;
  created: number;
  unchanged: number;
  notApplied: number;
  rejected: number;
}

export interface ImportRecord {
  id: number;
  fileName: string;
  created: number;
  updated: number;
  unchanged: number;
  rejected: number;
  summary: string | null;
  createdAt: string;
}

/** Import d'un fichier Excel : aperçu (rien n'est écrit), puis confirmation avec le même fichier. */
@Injectable({ providedIn: 'root' })
export class ImportsApi {
  private readonly http = inject(HttpClient);

  /** Adresse du modèle à télécharger (lien direct : la session suit par cookie). */
  readonly templateUrl = '/api/imports/template';

  preview(file: File): Promise<ImportPreview> {
    return firstValueFrom(this.http.post<ImportPreview>('/api/imports/preview', this.form(file)));
  }

  apply(file: File): Promise<ImportResult> {
    return firstValueFrom(this.http.post<ImportResult>('/api/imports', this.form(file)));
  }

  history(): Promise<ImportRecord[]> {
    return firstValueFrom(this.http.get<ImportRecord[]>('/api/imports'));
  }

  private form(file: File): FormData {
    const form = new FormData();
    form.append('file', file, file.name);
    return form;
  }
}
