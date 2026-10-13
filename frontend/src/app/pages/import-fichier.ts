import { DatePipe } from '@angular/common';
import { Component, OnInit, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { ImportAction, ImportPreview, ImportRecord, ImportResult, ImportsApi } from '../imports/imports-api';
import { apiErrorMessage } from '../shared/api-error';
import { frenchDate, scheduleLabel } from '../shared/schedule-label';

const ACTIONS: Record<ImportAction, string> = {
  CREATE: 'À créer',
  UPDATE: 'Modifiée (non appliquée)',
  UNCHANGED: 'Inchangée',
  REJECTED: 'Rejetée',
};

@Component({
  selector: 'app-import-fichier',
  imports: [RouterLink, DatePipe],
  styleUrls: ['../shared/page.scss', './import-fichier.scss'],
  template: `
    <h1>Importer un fichier</h1>

    <section class="bloc" aria-labelledby="titre-fichier">
      <h2 id="titre-fichier">1. Choisir le fichier</h2>
      <p class="aide">
        Partez du <a [href]="api.templateUrl" download>modèle Excel</a> : il contient les colonnes attendues, des listes
        déroulantes, des exemples et une feuille d'instructions. Rien n'est enregistré avant votre confirmation.
      </p>
      <input #choix type="file" accept=".xlsx,application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
             aria-label="Fichier Excel à importer" (change)="choisir(choix.files)" [disabled]="enCours()" />
    </section>

    @if (message(); as m) {
      <p [class]="m.erreur ? 'erreur' : 'info'" [attr.role]="m.erreur ? 'alert' : 'status'">{{ m.texte }}</p>
    }

    @if (resultat(); as r) {
      <section class="bloc resultat" aria-labelledby="titre-resultat">
        <h2 id="titre-resultat">Import terminé : {{ r.fileName }}</h2>
        <p>
          {{ r.created }} relance(s) créée(s), {{ r.unchanged }} inchangée(s), {{ r.rejected }} ligne(s) rejetée(s)@if (r.notApplied > 0) {,
            {{ r.notApplied }} modification(s) non appliquée(s)}.
        </p>
        <p><a routerLink="/lignes">Voir mes relances</a></p>
      </section>
    }

    @if (apercu(); as a) {
      <section class="bloc" aria-labelledby="titre-apercu">
        <h2 id="titre-apercu">2. Vérifier l'aperçu : {{ a.fileName }}</h2>

        @if (a.missingColumns.length > 0) {
          <p class="erreur" role="alert">
            Fichier refusé : colonnes obligatoires absentes ({{ a.missingColumns.join(', ') }}). Partez du modèle.
          </p>
        } @else {
          <ul class="compteurs">
            <li><strong>{{ a.counts.toCreate }}</strong> à créer</li>
            <li><strong>{{ a.counts.unchanged }}</strong> inchangée(s)</li>
            <li><strong>{{ a.counts.rejected }}</strong> rejetée(s)</li>
            <li><strong>{{ a.counts.withWarnings }}</strong> avec avertissement</li>
            <li><strong>{{ a.counts.plannedSends }}</strong> envoi(s) prévu(s)</li>
          </ul>
          @if (a.counts.toUpdate > 0) {
            <p class="info" role="note">
              {{ a.counts.toUpdate }} relance(s) existante(s) diffèrent du fichier : elles ne seront pas modifiées
              (la mise à jour par ré-import arrivera dans une prochaine version).
            </p>
          }

          <table>
            <thead>
              <tr>
                <th scope="col">Ligne</th>
                <th scope="col">id</th>
                <th scope="col">Action</th>
                <th scope="col">Relance telle que comprise</th>
                <th scope="col">Envois</th>
              </tr>
            </thead>
            <tbody>
              @for (ligne of a.rows; track ligne.rowNumber) {
                <tr [class]="'action-' + ligne.action.toLowerCase()">
                  <td>{{ ligne.rowNumber }}</td>
                  <td>{{ ligne.id || '—' }}</td>
                  <td><span class="badge">{{ libelleAction(ligne.action) }}</span></td>
                  <td>
                    @if (ligne.interpreted; as i) {
                      <div><strong>{{ i.email }}</strong> — {{ i.subject }}</div>
                      <div class="detail">{{ calendrier(i) }}</div>
                      <div class="detail">
                        Validation quotidienne : {{ i.dailyValidation ? 'oui' : 'non' }}
                        @if (i.status === 'CLOSED') { — statut clos }
                      </div>
                    }
                    @for (erreur of ligne.errors; track erreur) {
                      <div class="erreur">{{ erreur }}</div>
                    }
                    @for (avertissement of ligne.warnings; track avertissement) {
                      <div class="avertissement">{{ avertissement }}</div>
                    }
                  </td>
                  <td>
                    @if (ligne.sends; as e) {
                      @if (e.planned > 0) {
                        {{ e.planned }} envoi(s)<br />
                        <span class="detail">du {{ date(e.first) }} au {{ date(e.last) }}</span>
                      } @else {
                        —
                      }
                      @if (e.missedToday > 0) {
                        <div class="avertissement">Envoi du jour : heure passée, il ne partira pas.</div>
                      }
                    }
                  </td>
                </tr>
              }
            </tbody>
          </table>

          <div class="confirmation">
            <button type="submit" (click)="confirmer()" [disabled]="enCours() || a.counts.toCreate === 0">
              Confirmer l'import ({{ a.counts.toCreate }} relance(s) à créer)
            </button>
            <button type="button" (click)="annuler()" [disabled]="enCours()">Annuler</button>
          </div>
        }
      </section>
    }

    @if (historique().length > 0) {
      <section class="bloc" aria-labelledby="titre-historique">
        <h2 id="titre-historique">Derniers imports</h2>
        <ul class="historique">
          @for (h of historique(); track h.id) {
            <li>
              {{ h.createdAt | date: 'dd/MM/yyyy HH:mm' }} — {{ h.fileName }} : {{ h.created }} créée(s),
              {{ h.unchanged }} inchangée(s), {{ h.rejected }} rejetée(s)
              @if (h.summary) { <span class="detail">({{ h.summary }})</span> }
            </li>
          }
        </ul>
      </section>
    }
  `,
})
export class ImportFichier implements OnInit {
  protected readonly api = inject(ImportsApi);

  protected readonly fichier = signal<File | null>(null);
  protected readonly apercu = signal<ImportPreview | null>(null);
  protected readonly resultat = signal<ImportResult | null>(null);
  protected readonly historique = signal<ImportRecord[]>([]);
  protected readonly message = signal<{ texte: string; erreur: boolean } | null>(null);
  protected readonly enCours = signal(false);

  protected readonly calendrier = scheduleLabel;
  protected readonly date = frenchDate;

  async ngOnInit(): Promise<void> {
    await this.rechargerHistorique();
  }

  protected libelleAction(action: ImportAction): string {
    return ACTIONS[action];
  }

  protected async choisir(fichiers: FileList | null): Promise<void> {
    const fichier = fichiers?.item(0) ?? null;
    this.resultat.set(null);
    this.apercu.set(null);
    this.message.set(null);
    this.fichier.set(fichier);
    if (!fichier) {
      return;
    }
    this.enCours.set(true);
    try {
      this.apercu.set(await this.api.preview(fichier));
    } catch (error) {
      this.message.set({ texte: apiErrorMessage(error), erreur: true });
    } finally {
      this.enCours.set(false);
    }
  }

  protected async confirmer(): Promise<void> {
    const fichier = this.fichier();
    if (!fichier) {
      return;
    }
    this.enCours.set(true);
    try {
      this.resultat.set(await this.api.apply(fichier));
      this.apercu.set(null);
      this.fichier.set(null);
      await this.rechargerHistorique();
    } catch (error) {
      this.message.set({ texte: apiErrorMessage(error), erreur: true });
    } finally {
      this.enCours.set(false);
    }
  }

  protected annuler(): void {
    this.apercu.set(null);
    this.fichier.set(null);
    this.message.set({ texte: 'Import annulé : rien n’a été enregistré.', erreur: false });
  }

  private async rechargerHistorique(): Promise<void> {
    try {
      this.historique.set((await this.api.history()).slice(0, 5));
    } catch {
      // l'historique est secondaire : son absence n'empêche pas d'importer
    }
  }
}
