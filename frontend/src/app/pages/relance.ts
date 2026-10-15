import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { Auth } from '../auth/auth';
import { OccurrenceView, RuleDetail, RulesApi } from '../imports/rules-api';
import { apiErrorMessage } from '../shared/api-error';
import {
  OCCURRENCE_STATES,
  OccurrenceState,
  SendProblem,
  activationMessage,
  closeMessage,
  closeQuestion,
  forecastLabel,
  pauseMessage,
  ruleStatusLabel,
  sendProblem,
  testSendProblem,
} from '../shared/rule-labels';
import { frenchDate, scheduleLabel } from '../shared/schedule-label';
import { InstallationTimePipe, formatInZone } from '../shared/installation-time';
import { RelanceModification } from './relance-modification';

/** Envois encore à venir (ni partis, ni abandonnés). */
const UPCOMING: OccurrenceState[] = ['PLANNED', 'AWAITING_VALIDATION', 'VALIDATED'];

@Component({
  selector: 'app-relance',
  imports: [RouterLink, InstallationTimePipe, RelanceModification],
  styleUrls: ['../shared/page.scss', './import-fichier.scss', './relance.scss'],
  template: `
    <p><a routerLink="/lignes">← Toutes les relances</a></p>

    @if (erreur()) {
      <p class="erreur" role="alert">{{ erreur() }}</p>
    }

    @if (detail(); as d) {
      <div class="entete-liste">
        <h1>Relance {{ d.rule.businessId }} <span class="badge">{{ statut(d.rule) }}</span></h1>
        <div class="actions">
          <button type="button" (click)="mEnvoyer()" [disabled]="enCours()">M'envoyer cette relance</button>
          @if (!d.rule.activatedAt && d.rule.status === 'ACTIVE') {
            <button type="submit" (click)="activer()" [disabled]="enCours()">Activer</button>
          }
          @if (d.rule.activatedAt && d.rule.status === 'ACTIVE') {
            <button type="button" (click)="basculerPause()" [disabled]="enCours()">
              {{ d.rule.paused ? 'Reprendre' : 'Mettre en pause' }}
            </button>
          }
          @if (d.rule.status === 'ACTIVE' && !modification()) {
            <button type="button" (click)="modification.set(true); message.set(null)" [disabled]="enCours()">
              Modifier
            </button>
          }
          @if (d.rule.status === 'ACTIVE') {
            <button type="button" (click)="clore()" [disabled]="enCours()">Clore ce dossier</button>
          }
        </div>
      </div>

      @if (message(); as m) {
        <p [class]="m.erreur ? 'erreur' : 'info'" [attr.role]="m.erreur ? 'alert' : 'status'">{{ m.texte }}</p>
      }

      @if (modification()) {
        <app-relance-modification [detail]="d" (enregistre)="apresModification($event)"
                                  (annule)="modification.set(false)" />
      } @else {
      <section class="bloc" aria-labelledby="titre-message">
        <h2 id="titre-message">Message</h2>
        <dl class="fiche">
          <dt>Destinataire</dt>
          <dd>
            {{ d.rule.recipientEmail }}
            @if (d.rule.firstName || d.rule.lastName) { ({{ d.rule.firstName }} {{ d.rule.lastName }}) }
          </dd>
          @if (d.rule.caseReference) {
            <dt>Référence du dossier</dt>
            <dd>{{ d.rule.caseReference }}</dd>
          }
          <dt>Objet</dt>
          <dd>{{ d.rule.subject }}</dd>
          <dt>Contenu</dt>
          <dd class="contenu">{{ d.body }}</dd>
        </dl>
        <p class="aide">Les variables comme {{ '{{' }}prenom{{ '}}' }} seront remplacées au moment de l'envoi.</p>
      </section>

      <section class="bloc" aria-labelledby="titre-calendrier">
        <h2 id="titre-calendrier">Calendrier</h2>
        <p>{{ calendrier(d.rule) }}</p>
        <p>Validation quotidienne : {{ d.rule.dailyValidation ? 'oui — rien ne part sans votre validation du jour' : 'non' }}</p>
        @if (d.rule.forecast; as f) {
          <p class="prevision">{{ prevision(f) }}</p>
        }
      </section>
      }

      <section class="bloc" aria-labelledby="titre-envois">
        <h2 id="titre-envois">Envois</h2>
        @if (fuseau()) {
          <p class="aide">Heures dans le fuseau de l'installation : {{ fuseau() }}.</p>
        }
        @if (d.sends.length === 0) {
          <p class="info">
            {{ d.rule.activatedAt ? 'Aucun envoi.' : 'Aucun envoi tant que la relance n’est pas activée.' }}
          </p>
        } @else {
          <ul class="compteurs">
            <li><strong>{{ total('SENT') }}</strong> envoyé(s)</li>
            <li><strong>{{ aVenir() }}</strong> à venir</li>
            @for (etat of autresEtats(); track etat.code) {
              <li><strong>{{ etat.nombre }}</strong> {{ etat.libelle.toLowerCase() }}</li>
            }
          </ul>
          <table>
            <thead>
              <tr>
                <th scope="col">Jour prévu</th>
                <th scope="col">Heure</th>
                <th scope="col">État</th>
                <th scope="col">Détail</th>
              </tr>
            </thead>
            <tbody>
              @for (e of d.sends; track e.id) {
                <tr [class]="'etat-' + e.state.toLowerCase()">
                  <td>{{ date(e.scheduledDate) }}</td>
                  <td>{{ e.scheduledAt | heure: 'HH:mm' }}</td>
                  <td><span class="badge">{{ etat(e.state) }}</span></td>
                  <td class="detail">
                    @if (e.sentAt) {
                      Envoyé le {{ e.sentAt | heure: 'dd/MM/yyyy à HH:mm' }}@if (e.attempts > 1) { après
                      {{ e.attempts }} essais}.
                    }
                    @if (e.validatedAt) { Validé le {{ e.validatedAt | heure: 'dd/MM/yyyy à HH:mm' }}. }
                    @if (probleme(e, d.maxAttempts); as p) {
                      <div [class]="'probleme ' + p.level">{{ p.text }}</div>
                      @if (p.technical) {
                        <details class="technique">
                          <summary>Détail technique</summary>
                          {{ p.technical }}
                        </details>
                      }
                    }
                    @if (e.state === 'TO_CHECK') {
                      <div class="actions verification">
                        <button type="button" (click)="trancher(e.id, true)" [disabled]="enCours()">Il est parti</button>
                        <button type="button" (click)="trancher(e.id, false)" [disabled]="enCours()">
                          Il n'est pas parti
                        </button>
                      </div>
                    }
                  </td>
                </tr>
              }
            </tbody>
          </table>
        }
      </section>
    }
  `,
})
export class Relance implements OnInit {
  private readonly api = inject(RulesApi);
  private readonly auth = inject(Auth);
  private readonly id = Number(inject(ActivatedRoute).snapshot.paramMap.get('id'));

  protected readonly detail = signal<RuleDetail | null>(null);
  protected readonly erreur = signal<string | null>(null);
  protected readonly message = signal<{ texte: string; erreur: boolean } | null>(null);
  protected readonly enCours = signal(false);
  protected readonly modification = signal(false);

  protected readonly calendrier = scheduleLabel;
  protected readonly prevision = forecastLabel;
  protected readonly statut = ruleStatusLabel;
  protected readonly date = frenchDate;
  protected readonly fuseau = computed(() => this.auth.user()?.timezone);

  protected readonly aVenir = computed(() => UPCOMING.reduce((n, s) => n + this.total(s), 0));
  /** Envois ni envoyés ni à venir (manqués, annulés, échecs…), seulement les états présents. */
  protected readonly autresEtats = computed(() =>
    (Object.keys(OCCURRENCE_STATES) as OccurrenceState[])
      .filter((s) => s !== 'SENT' && !UPCOMING.includes(s) && this.total(s) > 0)
      .map((s) => ({ code: s, libelle: OCCURRENCE_STATES[s], nombre: this.total(s) })),
  );

  async ngOnInit(): Promise<void> {
    try {
      this.detail.set(await this.api.detail(this.id));
    } catch (error) {
      this.erreur.set(apiErrorMessage(error));
    }
  }

  protected probleme(e: OccurrenceView, maxAttempts: number): SendProblem | null {
    return sendProblem(e, maxAttempts, e.nextAttemptAt
      ? formatInZone(e.nextAttemptAt, 'dd/MM/yyyy à HH:mm', this.fuseau())
      : null);
  }

  protected etat(state: OccurrenceState): string {
    return OCCURRENCE_STATES[state];
  }

  protected total(state: OccurrenceState): number {
    return this.detail()?.countsByState[state] ?? 0;
  }

  protected async activer(): Promise<void> {
    await this.executer(async () => activationMessage(await this.api.activate(this.id)));
  }

  protected async basculerPause(): Promise<void> {
    const rule = this.detail()!.rule;
    await this.executer(async () => pauseMessage(rule.businessId, await this.api.setPaused(this.id, !rule.paused)));
  }

  /** Rendu exact envoyé à l'utilisateur lui-même, pour vérifier le message ; rien n'est planifié. */
  protected async mEnvoyer(): Promise<void> {
    this.enCours.set(true);
    try {
      const resultat = await this.api.sendToMyself(this.id);
      this.message.set(resultat.success
        ? { texte: `Relance envoyée à votre adresse (${resultat.to}) pour vérification.`, erreur: false }
        : { texte: testSendProblem(resultat.issue, resultat.detail), erreur: true });
    } catch (error) {
      this.message.set({ texte: apiErrorMessage(error), erreur: true });
    } finally {
      this.enCours.set(false);
    }
  }

  /** Après vérification (messagerie, destinataire) : l'envoi est-il parti ? Il ne sera jamais renvoyé. */
  protected async trancher(sendId: number, parti: boolean): Promise<void> {
    const question = parti
      ? 'Confirmez-vous que ce mail est bien parti ?'
      : "Confirmez-vous que ce mail n'est pas parti ? Il ne sera pas renvoyé automatiquement.";
    if (!confirm(question)) {
      return;
    }
    await this.executer(async () => {
      await this.api.resolveToCheck(this.id, sendId, parti);
      return parti ? 'Envoi marqué « Envoyé ».' : 'Envoi marqué « Échec » (non parti).';
    });
  }

  protected async apresModification(texte: string): Promise<void> {
    this.modification.set(false);
    await this.executer(async () => texte);
  }

  protected async clore(): Promise<void> {
    const businessId = this.detail()!.rule.businessId;
    if (!confirm(closeQuestion(businessId))) {
      return;
    }
    await this.executer(async () => closeMessage(businessId, (await this.api.close(this.id)).cancelled));
  }

  private async executer(action: () => Promise<string>): Promise<void> {
    this.enCours.set(true);
    try {
      this.message.set({ texte: await action(), erreur: false });
      this.detail.set(await this.api.detail(this.id));
    } catch (error) {
      this.message.set({ texte: apiErrorMessage(error), erreur: true });
    } finally {
      this.enCours.set(false);
    }
  }
}
