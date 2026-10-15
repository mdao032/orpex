import { Component, OnInit, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import {
  FieldChange, ImportAction, ImportPreview, ImportRecord, ImportResult, ImportsApi,
} from '../imports/imports-api';
import { apiErrorMessage } from '../shared/api-error';
import { daysRange, scheduleLabel } from '../shared/schedule-label';
import { InstallationTimePipe } from '../shared/installation-time';

/** Au-delà, la valeur est proposée repliée (texte du message, par exemple). */
const LONG_VALUE = 60;

const ACTIONS: Record<ImportAction, string> = {
  CREATE: 'À créer',
  UPDATE: 'À modifier',
  UNCHANGED: 'Inchangée',
  REJECTED: 'Rejetée',
};

@Component({
  selector: 'app-import-fichier',
  imports: [RouterLink, InstallationTimePipe],
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
          {{ r.created }} relance(s) créée(s), {{ r.updated }} modifiée(s), {{ r.unchanged }} inchangée(s),
          {{ r.rejected }} ligne(s) rejetée(s)@if (r.closedAbsent > 0) {, {{ r.closedAbsent }} relance(s) absente(s)
            du fichier close(s)}.
        </p>
        @if (r.sendsAdded + r.sendsMoved + r.sendsCancelled > 0) {
          <p>Envois à venir : {{ impact(r.sendsAdded, r.sendsMoved, r.sendsCancelled) }}.</p>
        }
        @if (r.created > 0) {
          <p>
            Les relances créées n'envoient rien tant qu'elles ne sont pas activées :
            <a routerLink="/lignes">voir et activer mes relances</a>.
          </p>
        }
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
            <li><strong>{{ a.counts.toUpdate }}</strong> à modifier</li>
            <li><strong>{{ a.counts.unchanged }}</strong> inchangée(s)</li>
            <li><strong>{{ a.counts.rejected }}</strong> rejetée(s)</li>
            <li><strong>{{ a.counts.withWarnings }}</strong> avec avertissement</li>
          </ul>
          @if (a.counts.toCreate > 0) {
            <p class="detail">Nouvelles relances : {{ a.counts.plannedSends }} envoi(s) prévu(s) une fois activées.</p>
          }
          @if (a.counts.sendsAdded + a.counts.sendsMoved + a.counts.sendsCancelled > 0) {
            <p class="info" role="note">
              Relances déjà activées, envois à venir :
              {{ impact(a.counts.sendsAdded, a.counts.sendsMoved, a.counts.sendsCancelled) }}. Les envois passés ne
              changent pas.
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
                  <td>
                    <span class="badge">{{ libelleAction(ligne.action) }}</span>
                    @if (ligne.closing) { <span class="badge cloture">Sera close</span> }
                  </td>
                  <td>
                    @if (ligne.interpreted; as i) {
                      <div><strong>{{ i.email }}</strong> — {{ i.subject }}</div>
                      <div class="detail">{{ calendrier(i) }}</div>
                      <div class="detail">
                        Validation quotidienne : {{ i.dailyValidation ? 'oui' : 'non' }}
                        @if (i.status === 'CLOSED') { — statut clos }
                      </div>
                    }
                    @if (ligne.changes.length > 0) {
                      <ul class="changements" aria-label="Champs modifiés">
                        @for (c of ligne.changes; track c.field) {
                          <li>
                            <span class="champ">{{ c.field }}</span> :
                            @if (estLong(c)) {
                              modifié
                              <details>
                                <summary>Voir l'ancienne et la nouvelle valeur</summary>
                                <p class="avant">Avant : {{ c.before ?? '(vide)' }}</p>
                                <p>Après : {{ c.after ?? '(vide)' }}</p>
                              </details>
                            } @else {
                              <span class="avant">{{ c.before ?? '(vide)' }}</span> → {{ c.after ?? '(vide)' }}
                            }
                          </li>
                        }
                      </ul>
                    }
                    @for (erreur of ligne.errors; track erreur) {
                      <div class="erreur">{{ erreur }}</div>
                    }
                    @for (avertissement of ligne.warnings; track avertissement) {
                      <div class="avertissement">{{ avertissement }}</div>
                    }
                  </td>
                  <td>
                    @if (ligne.action === 'UPDATE') {
                      @if (ligne.sendImpact; as s) {
                        @if (s.added + s.moved + s.cancelled > 0) {
                          {{ impact(s.added, s.moved, s.cancelled) }}
                        } @else {
                          Envois à venir inchangés
                        }
                      } @else {
                        <span class="detail">Pas encore activée : aucun envoi</span>
                      }
                    } @else if (ligne.sends; as e) {
                      @if (e.planned > 0) {
                        {{ e.planned }} envoi(s)<br />
                        <span class="detail">{{ jours(e.first, e.last) }}</span>
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

          @if (a.absent.length > 0) {
            <div class="absents">
              <h3>Relances actives absentes du fichier</h3>
              <p class="detail">Par défaut, elles ne changent pas.</p>
              <ul>
                @for (r of a.absent; track r.ruleId) {
                  <li><a [routerLink]="['/lignes', r.ruleId]">{{ r.businessId }}</a> — {{ r.subject }}</li>
                }
              </ul>
              <label>
                <input type="checkbox" [checked]="clore()" (change)="clore.set($any($event.target).checked)"
                       [disabled]="enCours()" />
                Clore ces {{ a.absent.length }} relance(s) : leurs envois à venir seront annulés
              </label>
            </div>
          }

          <div class="confirmation">
            <button type="submit" (click)="confirmer()" [disabled]="enCours() || !aAppliquer(a)">
              Confirmer l'import ({{ resume(a) }})
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
              {{ h.createdAt | heure: 'dd/MM/yyyy HH:mm' }} — {{ h.fileName }} : {{ h.created }} créée(s),
              {{ h.updated }} modifiée(s), {{ h.unchanged }} inchangée(s), {{ h.rejected }} rejetée(s)
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
  /** Clore les relances absentes du fichier : décoché par défaut, choix explicite. */
  protected readonly clore = signal(false);

  protected readonly calendrier = scheduleLabel;
  protected readonly jours = daysRange;

  async ngOnInit(): Promise<void> {
    await this.rechargerHistorique();
  }

  protected libelleAction(action: ImportAction): string {
    return ACTIONS[action];
  }

  protected estLong(change: FieldChange): boolean {
    return (change.before?.length ?? 0) > LONG_VALUE || (change.after?.length ?? 0) > LONG_VALUE;
  }

  protected impact(added: number, moved: number, cancelled: number): string {
    const parts: string[] = [];
    if (moved > 0) parts.push(`${moved} déplacé(s)`);
    if (cancelled > 0) parts.push(`${cancelled} annulé(s)`);
    if (added > 0) parts.push(`${added} ajouté(s)`);
    return parts.join(', ');
  }

  protected aAppliquer(a: ImportPreview): boolean {
    return a.counts.toCreate + a.counts.toUpdate > 0 || (this.clore() && a.absent.length > 0);
  }

  protected resume(a: ImportPreview): string {
    const parts = [`${a.counts.toCreate} à créer`, `${a.counts.toUpdate} à modifier`];
    if (this.clore() && a.absent.length > 0) parts.push(`${a.absent.length} à clore`);
    return parts.join(', ');
  }

  protected async choisir(fichiers: FileList | null): Promise<void> {
    const fichier = fichiers?.item(0) ?? null;
    this.resultat.set(null);
    this.apercu.set(null);
    this.message.set(null);
    this.clore.set(false);
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
      this.resultat.set(await this.api.apply(fichier, this.clore()));
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
