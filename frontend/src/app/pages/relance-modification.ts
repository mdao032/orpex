import { Component, OnInit, inject, input, output, signal } from '@angular/core';
import { EditResult, RuleDetail, RuleEdit, RulesApi } from '../imports/rules-api';
import { apiErrorMessage } from '../shared/api-error';
import { sendImpactLabel } from '../shared/rule-labels';

/** Question posée avant de passer la validation quotidienne de oui à non (spécification §7). */
export const VALIDATION_DROP_QUESTION =
  'La validation quotidienne passe de « oui » à « non » : les envois à venir partiront sans votre validation. '
  + 'Confirmez-vous ?';

/** Formulaire de modification d'une relance : mêmes règles qu'à l'import, envois futurs recalculés. */
@Component({
  selector: 'app-relance-modification',
  styleUrls: ['../shared/page.scss', './relance.scss'],
  template: `
    <form class="bloc modification" (submit)="$event.preventDefault(); enregistrer()" aria-labelledby="titre-modif">
      <h2 id="titre-modif">Modifier la relance</h2>
      @if (detail().rule.activatedAt) {
        <p class="aide">
          Les envois à venir seront recalculés ; les envois passés ne changent pas.
        </p>
      }

      <fieldset>
        <legend>Message</legend>
        <label>Email du destinataire
          <input type="email" required [value]="m().email" (input)="champ('email', $event)" />
        </label>
        <div class="ligne">
          <label>Prénom <input [value]="m().firstName" (input)="champ('firstName', $event)" /></label>
          <label>Nom <input [value]="m().lastName" (input)="champ('lastName', $event)" /></label>
          <label>Référence du dossier
            <input [value]="m().caseReference" (input)="champ('caseReference', $event)" />
          </label>
        </div>
        <label>Objet <input required [value]="m().subject" (input)="champ('subject', $event)" /></label>
        <label>Contenu
          <textarea required rows="8" [value]="m().body" (input)="champ('body', $event)"></textarea>
        </label>
        <p class="aide">
          Variables possibles : {{ '{{' }}prenom{{ '}}' }}, {{ '{{' }}nom{{ '}}' }},
          {{ '{{' }}reference_dossier{{ '}}' }} et les colonnes libres du dernier import. Leurs valeurs se modifient
          par ré-import.
        </p>
      </fieldset>

      <fieldset>
        <legend>Calendrier</legend>
        <div class="ligne">
          <label>Mode d'envoi
            <select [value]="m().sendMode" (change)="champ('sendMode', $event)">
              <option value="PONCTUEL">Ponctuel (un seul envoi)</option>
              <option value="AVANT_ECHEANCE">Avant une échéance</option>
              <option value="PERIODE">Sur une période</option>
            </select>
          </label>
          @switch (m().sendMode) {
            @case ('PONCTUEL') {
              <label>Date d'envoi
                <input type="date" required [value]="m().sendDate" (input)="champ('sendDate', $event)" />
              </label>
            }
            @case ('AVANT_ECHEANCE') {
              <label>Échéance
                <input type="date" required [value]="m().dueDate" (input)="champ('dueDate', $event)" />
              </label>
              <label>Jours avant
                <input type="number" min="0" required [value]="m().daysBefore" (input)="champ('daysBefore', $event)" />
              </label>
            }
            @case ('PERIODE') {
              <label>Du
                <input type="date" required [value]="m().startDate" (input)="champ('startDate', $event)" />
              </label>
              <label>Au
                <input type="date" required [value]="m().endDate" (input)="champ('endDate', $event)" />
              </label>
            }
          }
          <label>Heure d'envoi
            <input type="time" required [value]="m().sendTime" (input)="champ('sendTime', $event)" />
          </label>
        </div>
        <label class="case">
          <input type="checkbox" [checked]="m().businessDaysOnly" (change)="caseACocher('businessDaysOnly', $event)" />
          Jours ouvrés uniquement (sans week-ends ni jours fériés)
        </label>
        <label class="case">
          <input type="checkbox" [checked]="m().dailyValidation" (change)="caseACocher('dailyValidation', $event)" />
          Validation quotidienne : rien ne part sans ma validation du jour
        </label>
      </fieldset>

      @if (erreur()) {
        <p class="erreur" role="alert">{{ erreur() }}</p>
      }
      <div class="actions">
        <button type="submit" [disabled]="enCours()">Enregistrer</button>
        <button type="button" (click)="annule.emit()" [disabled]="enCours()">Annuler</button>
      </div>
    </form>
  `,
})
export class RelanceModification implements OnInit {
  private readonly api = inject(RulesApi);

  readonly detail = input.required<RuleDetail>();
  /** Message à afficher après l'enregistrement. */
  readonly enregistre = output<string>();
  readonly annule = output<void>();

  protected readonly m = signal<RuleEdit>({} as RuleEdit);
  protected readonly erreur = signal<string | null>(null);
  protected readonly enCours = signal(false);

  ngOnInit(): void {
    const { rule, body } = this.detail();
    this.m.set({
      email: rule.recipientEmail,
      firstName: rule.firstName ?? '',
      lastName: rule.lastName ?? '',
      caseReference: rule.caseReference ?? '',
      subject: rule.subject,
      body,
      sendMode: rule.sendMode,
      sendDate: rule.sendDate,
      dueDate: rule.dueDate,
      daysBefore: rule.daysBefore,
      startDate: rule.startDate,
      endDate: rule.endDate,
      sendTime: rule.sendTime.slice(0, 5),
      businessDaysOnly: rule.businessDaysOnly,
      dailyValidation: rule.dailyValidation,
    });
  }

  protected champ(nom: keyof RuleEdit, event: Event): void {
    const brut = (event.target as HTMLInputElement).value;
    const valeur = nom === 'daysBefore' ? (brut === '' ? null : Number(brut))
      : ['sendDate', 'dueDate', 'startDate', 'endDate'].includes(nom) ? brut || null
        : brut;
    this.m.update((m) => ({ ...m, [nom]: valeur }));
  }

  protected caseACocher(nom: 'businessDaysOnly' | 'dailyValidation', event: Event): void {
    const coche = (event.target as HTMLInputElement).checked;
    this.m.update((m) => ({ ...m, [nom]: coche }));
  }

  protected async enregistrer(): Promise<void> {
    const modele = this.m();
    const passeANon = this.detail().rule.dailyValidation && !modele.dailyValidation;
    if (passeANon && !confirm(VALIDATION_DROP_QUESTION)) {
      return;
    }
    this.enCours.set(true);
    this.erreur.set(null);
    try {
      const resultat = await this.api.edit(this.detail().rule.id, modele, passeANon);
      this.enregistre.emit(editMessage(resultat));
    } catch (error) {
      this.erreur.set(apiErrorMessage(error));
    } finally {
      this.enCours.set(false);
    }
  }
}

export function editMessage(r: EditResult): string {
  if (r.changes.length === 0) {
    return 'Aucune modification.';
  }
  const impact = sendImpactLabel(r.sendsAdded, r.sendsMoved, r.sendsCancelled);
  return `Relance modifiée (${r.changes.map((c) => c.field).join(', ')})`
    + (impact ? ` ; envois à venir : ${impact}.` : '.');
}
