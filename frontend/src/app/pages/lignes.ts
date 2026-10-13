import { Component, OnInit, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { RuleView, RulesApi } from '../imports/rules-api';
import { apiErrorMessage } from '../shared/api-error';
import { scheduleLabel } from '../shared/schedule-label';

@Component({
  selector: 'app-lignes',
  imports: [RouterLink],
  styleUrls: ['../shared/page.scss', './import-fichier.scss'],
  template: `
    <h1>Relances</h1>

    @if (erreur()) {
      <p class="erreur" role="alert">{{ erreur() }}</p>
    } @else if (charge() && relances().length === 0) {
      <p class="info">
        Aucune relance pour l'instant. <a routerLink="/import">Importez un fichier Excel</a> pour en créer.
      </p>
    } @else if (relances().length > 0) {
      <p class="aide">
        {{ relances().length }} relance(s). Les envois seront planifiés à l'activation (prochaine version).
      </p>
      <table>
        <thead>
          <tr>
            <th scope="col">id</th>
            <th scope="col">Destinataire</th>
            <th scope="col">Objet</th>
            <th scope="col">Calendrier</th>
            <th scope="col">Validation</th>
            <th scope="col">Statut</th>
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
              <td class="detail">{{ calendrier(r) }}</td>
              <td>{{ r.dailyValidation ? 'quotidienne' : 'non' }}</td>
              <td>{{ r.status === 'CLOSED' ? 'Clos' : r.paused ? 'En pause' : 'Actif' }}</td>
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
}
