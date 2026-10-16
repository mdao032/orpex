import { HttpClient } from '@angular/common/http';
import { Component, OnInit, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { firstValueFrom } from 'rxjs';
import { Auth, SummaryFrequency } from '../auth/auth';
import { apiErrorMessage } from '../shared/api-error';

const CHOIX: { valeur: SummaryFrequency; libelle: string; detail: string }[] = [
  {
    valeur: 'DAILY', libelle: 'Chaque jour ouvré, en fin de journée',
    detail: 'Les relances du jour et celles prévues le lendemain.',
  },
  {
    valeur: 'WEEKLY_FRIDAY', libelle: 'Chaque semaine, le vendredi en fin de journée',
    detail: 'Le bilan de la semaine et les relances prévues la semaine suivante.',
  },
  {
    valeur: 'WEEKLY_MONDAY', libelle: 'Chaque semaine, le lundi en début de journée',
    detail: 'Le bilan de la semaine précédente et les relances prévues dans la semaine.',
  },
];

/** Préférences de l'utilisateur : fréquence du résumé de ses relances, lien vers le changement de mot de passe. */
@Component({
  selector: 'app-mon-compte',
  imports: [RouterLink],
  styleUrls: ['../shared/page.scss', './mon-compte.scss'],
  template: `
    <h1>Mon compte</h1>

    <section class="bloc" aria-labelledby="titre-resume">
      <h2 id="titre-resume">Résumé de mes relances</h2>
      <p class="aide">
        Un mail récapitule vos envois : partis, en échec, non partis et à venir. Il n'est envoyé que s'il y a des envois
        sur la période. Les échecs, eux, vous sont signalés aussitôt, quelle que soit la fréquence choisie.
      </p>
      <fieldset class="choix" [disabled]="enCours()">
        <legend class="masque">Fréquence du résumé</legend>
        @for (c of choix; track c.valeur) {
          <label>
            <input type="radio" name="frequence" [checked]="frequence() === c.valeur" (change)="choisir(c.valeur)" />
            <span><strong>{{ c.libelle }}</strong><br /><span class="detail">{{ c.detail }}</span></span>
          </label>
        }
      </fieldset>
      <p class="aide">
        Les heures de début et de fin de journée sont réglées par l'administrateur. Si le vendredi ou le lundi est
        férié, le résumé part le jour ouvré le plus proche dans la semaine.
      </p>
      @if (message(); as m) {
        <p [class]="m.erreur ? 'erreur' : 'info'" [attr.role]="m.erreur ? 'alert' : 'status'">{{ m.texte }}</p>
      }
    </section>

    <section class="bloc" aria-labelledby="titre-securite">
      <h2 id="titre-securite">Mot de passe</h2>
      <a routerLink="/mot-de-passe">Changer mon mot de passe</a>
    </section>
  `,
})
export class MonCompte implements OnInit {
  private readonly http = inject(HttpClient);
  private readonly auth = inject(Auth);

  protected readonly choix = CHOIX;
  protected readonly frequence = signal<SummaryFrequency>('DAILY');
  protected readonly enCours = signal(false);
  protected readonly message = signal<{ texte: string; erreur: boolean } | null>(null);

  ngOnInit(): void {
    this.frequence.set(this.auth.user()?.summaryFrequency ?? 'DAILY');
  }

  protected async choisir(valeur: SummaryFrequency): Promise<void> {
    const avant = this.frequence();
    this.frequence.set(valeur);
    this.enCours.set(true);
    try {
      await firstValueFrom(this.http.put('/api/me/summary-frequency', { frequency: valeur }));
      await this.auth.refresh();
      const libelle = CHOIX.find((c) => c.valeur === valeur)!.libelle.toLowerCase();
      this.message.set({ texte: `Résumé : ${libelle}.`, erreur: false });
    } catch (error) {
      this.frequence.set(avant);
      this.message.set({ texte: apiErrorMessage(error), erreur: true });
    } finally {
      this.enCours.set(false);
    }
  }
}
