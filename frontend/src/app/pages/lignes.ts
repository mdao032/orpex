import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { RuleView, RulesApi } from '../imports/rules-api';
import { apiErrorMessage } from '../shared/api-error';
import {
  activationMessage,
  closeMessage,
  closeQuestion,
  forecastLabel,
  pauseMessage,
  ruleStatusLabel,
} from '../shared/rule-labels';
import { scheduleLabel } from '../shared/schedule-label';

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
          crée ses envois selon son calendrier. Cliquez sur une relance pour voir ses envois.
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
              <td><a [routerLink]="['/lignes', r.id]">{{ r.businessId }}</a></td>
              <td>
                {{ r.recipientEmail }}
                @if (r.caseReference) { <div class="detail">{{ r.caseReference }}</div> }
              </td>
              <td><a [routerLink]="['/lignes', r.id]">{{ r.subject }}</a></td>
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
                @if (r.activatedAt && r.status === 'ACTIVE') {
                  <button type="button" (click)="basculerPause(r)" [disabled]="enCours()">
                    {{ r.paused ? 'Reprendre' : 'Mettre en pause' }}
                  </button>
                }
                @if (r.status === 'ACTIVE') {
                  <button type="button" (click)="clore(r)" [disabled]="enCours()">Clore ce dossier</button>
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
  protected readonly prevision = forecastLabel;
  protected readonly statut = ruleStatusLabel;

  async ngOnInit(): Promise<void> {
    try {
      this.relances.set(await this.api.list());
    } catch (error) {
      this.erreur.set(apiErrorMessage(error));
    } finally {
      this.charge.set(true);
    }
  }

  protected async activer(r: RuleView): Promise<void> {
    await this.executer(async () => activationMessage(await this.api.activate(r.id)));
  }

  protected async basculerPause(r: RuleView): Promise<void> {
    await this.executer(async () => pauseMessage(r.businessId, await this.api.setPaused(r.id, !r.paused)));
  }

  protected async clore(r: RuleView): Promise<void> {
    if (!confirm(closeQuestion(r.businessId))) {
      return;
    }
    await this.executer(async () => closeMessage(r.businessId, (await this.api.close(r.id)).cancelled));
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
