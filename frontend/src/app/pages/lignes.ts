import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { ActivationResult, RuleView, RulesApi } from '../imports/rules-api';
import { apiErrorMessage } from '../shared/api-error';
import { frenchDate, scheduleLabel } from '../shared/schedule-label';

@Component({
  selector: 'app-lignes',
  imports: [RouterLink],
  styleUrls: ['../shared/page.scss', './import-fichier.scss'],
  template: `
    <h1>Relances</h1>

    @if (message(); as m) {
      <p [class]="m.erreur ? 'erreur' : 'info'" [attr.role]="m.erreur ? 'alert' : 'status'">{{ m.texte }}</p>
    }

    @if (erreur()) {
      <p class="erreur" role="alert">{{ erreur() }}</p>
    } @else if (charge() && relances().length === 0) {
      <p class="info">
        Aucune relance pour l'instant. <a routerLink="/import">Importez un fichier Excel</a> pour en créer.
      </p>
    } @else if (relances().length > 0) {
      <div class="entete-liste">
        <p class="aide">
          {{ relances().length }} relance(s). Une relance n'envoie rien tant qu'elle n'est pas activée : l'activation
          crée ses envois selon son calendrier.
        </p>
        @if (aActiver() > 0) {
          <button type="submit" (click)="activerTout()" [disabled]="enCours()">
            Activer les {{ aActiver() }} relance(s) non activée(s)
          </button>
        }
      </div>
      <table>
        <thead>
          <tr>
            <th scope="col">id</th>
            <th scope="col">Destinataire</th>
            <th scope="col">Objet</th>
            <th scope="col">Calendrier</th>
            <th scope="col">Validation</th>
            <th scope="col">Statut</th>
            <th scope="col"><span class="masque">Actions</span></th>
          </tr>
        </thead>
        <tbody>
          @for (r of relances(); track r.id) {
            <tr [class.inactif]="r.status === 'CLOSED'">
              <td>{{ r.businessId }}</td>
              <td>
                {{ r.recipientEmail }}
                @if (r.caseReference) { <div class="detail">{{ r.caseReference }}</div> }
              </td>
              <td>{{ r.subject }}</td>
              <td class="detail">
                {{ calendrier(r) }}
                @if (r.forecast; as f) {
                  <div class="prevision">{{ prevision(f) }}</div>
                }
              </td>
              <td>{{ r.dailyValidation ? 'quotidienne' : 'non' }}</td>
              <td>{{ statut(r) }}</td>
              <td class="actions">
                @if (!r.activatedAt && r.status === 'ACTIVE') {
                  <button type="button" (click)="activer(r)" [disabled]="enCours()">Activer</button>
                }
              </td>
            </tr>
          }
        </tbody>
      </table>
    }
  `,
})
export class Lignes implements OnInit {
  private readonly api = inject(RulesApi);

  protected readonly relances = signal<RuleView[]>([]);
  protected readonly charge = signal(false);
  protected readonly erreur = signal<string | null>(null);
  protected readonly message = signal<{ texte: string; erreur: boolean } | null>(null);
  protected readonly enCours = signal(false);
  protected readonly aActiver = computed(
    () => this.relances().filter((r) => !r.activatedAt && r.status === 'ACTIVE').length,
  );
  protected readonly calendrier = scheduleLabel;

  async ngOnInit(): Promise<void> {
    try {
      this.relances.set(await this.api.list());
    } catch (error) {
      this.erreur.set(apiErrorMessage(error));
    } finally {
      this.charge.set(true);
    }
  }

  protected prevision(f: NonNullable<RuleView['forecast']>): string {
    if (f.planned === 0) {
      return "À l'activation : aucun envoi à venir.";
    }
    return `À l'activation : ${f.planned} envoi(s) prévu(s) du ${frenchDate(f.first)} au ${frenchDate(f.last)}`
      + (f.missedToday > 0 ? " (celui d'aujourd'hui est déjà passé)." : '.');
  }

  protected statut(r: RuleView): string {
    if (r.status === 'CLOSED') {
      return 'Clos';
    }
    if (!r.activatedAt) {
      return 'Non activée';
    }
    return r.paused ? 'En pause' : 'Active';
  }

  protected async activer(r: RuleView): Promise<void> {
    await this.executer(async () => resume(await this.api.activate(r.id)));
  }

  protected async activerTout(): Promise<void> {
    await this.executer(async () => {
      const resultats = await this.api.activateAll();
      const envois = resultats.reduce((total, x) => total + x.planned, 0);
      return `${resultats.length} relance(s) activée(s) : ${envois} envoi(s) planifié(s).`;
    });
  }

  private async executer(action: () => Promise<string>): Promise<void> {
    this.enCours.set(true);
    try {
      this.message.set({ texte: await action(), erreur: false });
      this.relances.set(await this.api.list());
    } catch (error) {
      this.message.set({ texte: apiErrorMessage(error), erreur: true });
    } finally {
      this.enCours.set(false);
    }
  }
}

function resume(r: ActivationResult): string {
  const envois = r.planned === 0
    ? 'aucun envoi à venir'
    : `${r.planned} envoi(s) du ${frenchDate(r.first)} au ${frenchDate(r.last)}`;
  return `${r.businessId} activée : ${envois}` + (r.missed > 0 ? ' (envoi du jour manqué : heure passée).' : '.');
}
