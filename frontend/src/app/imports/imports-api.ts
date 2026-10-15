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
  /** Ligne modifiée : champs qui changent (valeurs affichables, null = vide). */
  changes: FieldChange[];
  /** Ligne modifiée d'une relance activée : effet sur ses envois futurs. */
  sendImpact: { added: number; moved: number; cancelled: number } | null;
  /** Le fichier passe la relance au statut clos : elle sera close, ses envois à venir annulés. */
  closing: boolean;
  /** Champs modifiés à l'écran pour lesquels le fichier propose une autre valeur : à trancher un par un. */
  conflicts: string[];
}

/** Choix pour un conflit : garder la valeur de l'application ou prendre celle du fichier. */
export type ConflictChoice = 'APP' | 'FILE';

/** Par id de ligne, le choix de chaque champ en conflit. */
export type Resolutions = Record<string, Record<string, ConflictChoice>>;

export interface FieldChange {
  field: string;
  before: string | null;
  after: string | null;
}

/** Relance active du propriétaire dont l'id n'est pas dans le fichier. */
export interface AbsentRule {
  ruleId: number;
  businessId: string;
  subject: string;
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
    sendsAdded: number;
    sendsMoved: number;
    sendsCancelled: number;
    withConflicts: number;
  };
  rows: RowPreview[];
  absent: AbsentRule[];
}

export interface ImportResult {
  importId: number;
  fileName: string;
  created: number;
  updated: number;
  unchanged: number;
  rejected: number;
  closedAbsent: number;
  sendsAdded: number;
  sendsMoved: number;
  sendsCancelled: number;
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

  /**
   * `closeAbsent` : clore aussi les relances actives absentes du fichier (choix explicite) ; `resolutions` : choix
   * de chaque conflit (tous obligatoires).
   */
  apply(file: File, closeAbsent = false, resolutions: Resolutions = {}): Promise<ImportResult> {
    const form = this.form(file);
    form.append('closeAbsent', String(closeAbsent));
    form.append('resolutions', JSON.stringify(resolutions));
    return firstValueFrom(this.http.post<ImportResult>('/api/imports', form));
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
