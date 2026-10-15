import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { RulesApi } from '../imports/rules-api';
import { apiErrorMessage } from '../shared/api-error';
import { InstallationTimePipe } from '../shared/installation-time';
import { closeMessage, closeQuestion } from '../shared/rule-labels';
import { Decision, Pending, ValidationApi } from '../validation/validation-api';

@Component({
  selector: 'app-validation-du-jour',
  imports: [RouterLink, InstallationTimePipe],
  styleUrls: ['../shared/page.scss', './import-fichier.scss', './validation-du-jour.scss'],
  template: `
    <h1>Validation du jour</h1>

    @if (message(); as m) {
      <p [class]="m.erreur ? 'erreur' : 'info'" [attr.role]="m.erreur ? 'alert' : 'status'">{{ m.texte }}</p>
    }

    @if (charge() && relances().length === 0) {
      <p class="info">Aucune relance n'attend votre validation aujourd'hui.</p>
    } @else if (relances().length > 0) {
      <p class="aide">
        Ces relances ne partiront que si vous les validez avant leur heure limite. Décochez celles qui ne doivent pas
        partir aujourd'hui (dossier réglé, situation changée) ; clôturez un dossier réglé pour arrêter ses relances.
        Jusqu'à l'heure limite, vous pouvez revenir sur votre décision.
      </p>
      <table>
        <thead>
          <tr>
            <th scope="col"><span class="masque">Valider</span></th>
            <th scope="col">Dossier</th>
            <th scope="col">Destinataire</th>
            <th scope="col">Objet</th>
            <th scope="col">Envoi</th>
            <th scope="col">Heure limite</th>
            <th scope="col">Déjà envoyées</th>
            <th scope="col"><span class="masque">Actions</span></th>
          </tr>
        </thead>
        <tbody>
          @for (r of relances(); track r.sendId) {
            <tr [class.inactif]="!r.open || r.decision === 'UNCHECKED'">
              <td>
                @if (r.decision === 'PENDING') {
                  <input type="checkbox" [checked]="coche(r)" [disabled]="!r.open || enCours()"
                         (change)="basculer(r.sendId)" [attr.aria-label]="'Valider ' + r.businessId" />
                }
              </td>
              <td>
                <a [routerLink]="['/lignes', r.ruleId]">{{ r.businessId }}</a>
                @if (r.caseReference) { <div class="detail">{{ r.caseReference }}</div> }
              </td>
              <td>{{ r.recipientEmail }}</td>
              <td>{{ r.subject }}</td>
              <td>{{ r.scheduledAt | heure: 'HH:mm' }}</td>
              <td>
                @if (r.open) {
                  {{ r.cutoffAt | heure: 'HH:mm' }}
                } @else if (r.decision === 'VALIDATED') {
                  Passée
                } @else {
                  <span class="erreur">Passée : ne partira pas</span>
                }
                @if (r.decision === 'VALIDATED') {
                  <div class="decision validee">Validée : partira à son heure</div>
                } @else if (r.decision === 'UNCHECKED') {
                  <div class="decision decochee">Décochée : ne partira pas</div>
                }
              </td>
              <td>{{ r.alreadySent }}</td>
              <td class="actions">
                @if (r.decision !== 'PENDING' && r.open) {
                  <button type="button" (click)="revenir(r)" [disabled]="enCours()">Revenir sur ma décision</button>
                }
                <button type="button" (click)="clore(r)" [disabled]="enCours()">Clore ce dossier</button>
              </td>
            </tr>
          }
        </tbody>
      </table>
      <div class="confirmation">
        <button type="submit" (click)="valider()" [disabled]="enCours() || ouvertes().length === 0">
          Valider la sélection ({{ selection().length }} sur {{ ouvertes().length }})
        </button>
        <button type="button" (click)="toutValider()" [disabled]="enCours() || ouvertes().length === 0">
          Tout valider
        </button>
      </div>
    }
  `,
})
export class ValidationDuJour implements OnInit {
  private readonly api = inject(ValidationApi);
  private readonly rulesApi = inject(RulesApi);

  protected readonly relances = signal<Pending[]>([]);
  protected readonly decoches = signal<Set<number>>(new Set());
  protected readonly charge = signal(false);
  protected readonly enCours = signal(false);
  protected readonly message = signal<{ texte: string; erreur: boolean } | null>(null);

  /** Envois encore à valider (en attente, heure limite non passée). */
  protected readonly ouvertes = computed(() => this.relances().filter((r) => r.open && r.decision === 'PENDING'));
  protected readonly selection = computed(() => this.ouvertes().filter((r) => !this.decoches().has(r.sendId)));

  async ngOnInit(): Promise<void> {
    await this.recharger();
  }

  protected coche(r: Pending): boolean {
    return r.open && !this.decoches().has(r.sendId);
  }

  protected basculer(sendId: number): void {
    const decoches = new Set(this.decoches());
    if (decoches.has(sendId)) {
      decoches.delete(sendId);
    } else {
      decoches.add(sendId);
    }
    this.decoches.set(decoches);
  }

  protected async valider(): Promise<void> {
    const valider = this.selection().map((r) => r.sendId);
    const refuser = this.ouvertes().filter((r) => this.decoches().has(r.sendId)).map((r) => r.sendId);
    if (refuser.length > 0
      && !confirm(`${refuser.length} relance(s) décochée(s) ne partiront pas aujourd'hui. Confirmer ?`)) {
      return;
    }
    await this.decider(valider, refuser);
  }

  protected async toutValider(): Promise<void> {
    await this.decider(this.ouvertes().map((r) => r.sendId), []);
  }

  protected async revenir(r: Pending): Promise<void> {
    await this.executer(async () => {
      await this.api.reopen(r.sendId);
      return `${r.businessId} : décision annulée, la relance attend de nouveau votre validation.`;
    });
  }

  protected async clore(r: Pending): Promise<void> {
    if (!confirm(closeQuestion(r.businessId))) {
      return;
    }
    await this.executer(async () => closeMessage(r.businessId, (await this.rulesApi.close(r.ruleId)).cancelled));
  }

  private async decider(valider: number[], refuser: number[]): Promise<void> {
    await this.executer(async () => resume(await this.api.decide(valider, refuser)));
  }

  private async executer(action: () => Promise<string>): Promise<void> {
    this.enCours.set(true);
    try {
      this.message.set({ texte: await action(), erreur: false });
      await this.recharger();
    } catch (error) {
      this.message.set({ texte: apiErrorMessage(error), erreur: true });
    } finally {
      this.enCours.set(false);
    }
  }

  private async recharger(): Promise<void> {
    try {
      this.relances.set(await this.api.today());
      this.decoches.set(new Set());
    } catch (error) {
      this.message.set({ texte: apiErrorMessage(error), erreur: true });
    } finally {
      this.charge.set(true);
    }
  }
}

function resume(d: Decision): string {
  const parts = [`${d.validated} relance(s) validée(s)`];
  if (d.rejected > 0) {
    parts.push(`${d.rejected} décochée(s) (ne partiront pas)`);
  }
  const refused = d.refused.length > 0 ? ` ${d.refused.length} non traitée(s) : ${d.refused[0].reason}` : '';
  return parts.join(', ') + '.' + refused;
}
