import { DatePipe } from '@angular/common';
import { Component, OnInit, inject, signal } from '@angular/core';
import { NonNullableFormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { apiErrorMessage } from '../shared/api-error';
import { Country, Holiday, HolidaysApi, rescheduledMessage } from './holidays-api';

@Component({
  selector: 'app-jours-feries',
  imports: [ReactiveFormsModule, DatePipe],
  styleUrl: '../shared/page.scss',
  template: `
    <h1>Jours fériés</h1>

    <section class="bloc" aria-labelledby="titre-ajout">
      <h2 id="titre-ajout">Ajouter un jour férié</h2>
      <form class="creation" [formGroup]="ajout" (ngSubmit)="ajouter()" novalidate>
        <label>Date
          <input type="date" formControlName="jour" required />
        </label>
        <label>Libellé
          <input type="text" formControlName="libelle" maxlength="200" required />
        </label>
        <button type="submit" [disabled]="enCours()">Ajouter</button>
      </form>
      <p class="aide">
        Avec « jours ouvrés uniquement », aucun envoi n'est prévu un jour férié. Un changement recalcule aussitôt
        les envois à venir des relances activées (les envois passés ne changent jamais).
      </p>
    </section>

    <section class="bloc" aria-labelledby="titre-pays">
      <h2 id="titre-pays">Jours fériés d'un pays</h2>
      <form class="creation" (submit)="ajouterPays(pays.value, annee.valueAsNumber); $event.preventDefault()">
        <label>Pays
          <select #pays>
            @for (p of pays_(); track p.code) {
              <option [value]="p.code">{{ p.label }}</option>
            }
          </select>
        </label>
        <label>Année
          <input #annee type="number" min="2000" max="2100" [value]="anneeCourante" required />
        </label>
        <button type="submit" [disabled]="enCours() || pays_().length === 0">Ajouter les jours fériés</button>
      </form>
      <p class="aide">
        Seuls les jours calculables avec certitude sont ajoutés (dates fixes, Pâques et fêtes qui en dépendent).
        Les dates déjà présentes sont conservées telles quelles.
      </p>
    </section>

    @if (message(); as m) {
      <p [class]="m.erreur ? 'erreur' : 'info'" [attr.role]="m.erreur ? 'alert' : 'status'">{{ m.texte }}</p>
    }

    @if (aSaisir().length > 0) {
      <p class="info" role="note">
        À saisir à la main une fois leur date officielle annoncée (calendrier lunaire) : {{ aSaisir().join(', ') }}.
      </p>
    }

    @if (joursFeries().length === 0) {
      <p class="info">Aucun jour férié : seuls les samedis et dimanches sont considérés comme non ouvrés.</p>
    } @else {
      <table>
        <thead>
          <tr>
            <th scope="col">Date</th>
            <th scope="col">Libellé</th>
            <th scope="col"><span class="masque">Actions</span></th>
          </tr>
        </thead>
        <tbody>
          @for (jour of joursFeries(); track jour.day) {
            <tr>
              <td>{{ jour.day | date: 'EEEE d MMMM y' }}</td>
              <td>{{ jour.label }}</td>
              <td class="actions">
                <button type="button" (click)="retirer(jour)" [disabled]="enCours()">Retirer</button>
              </td>
            </tr>
          }
        </tbody>
      </table>
    }
  `,
})
export class JoursFeries implements OnInit {
  private readonly api = inject(HolidaysApi);

  protected readonly joursFeries = signal<Holiday[]>([]);
  protected readonly message = signal<{ texte: string; erreur: boolean } | null>(null);
  protected readonly enCours = signal(false);
  protected readonly anneeCourante = new Date().getFullYear();
  protected readonly pays_ = signal<Country[]>([]);
  protected readonly aSaisir = signal<string[]>([]);

  protected readonly ajout = inject(NonNullableFormBuilder).group({
    jour: ['', Validators.required],
    libelle: ['', Validators.required],
  });

  async ngOnInit(): Promise<void> {
    await this.recharger();
    try {
      this.pays_.set(await this.api.countries());
    } catch (error) {
      this.message.set({ texte: apiErrorMessage(error), erreur: true });
    }
  }

  protected async ajouter(): Promise<void> {
    const { jour, libelle } = this.ajout.getRawValue();
    if (this.ajout.invalid || libelle.trim() === '') {
      this.message.set({ texte: 'Saisissez une date et un libellé.', erreur: true });
      return;
    }
    await this.executer(async () => {
      const ajoute = await this.api.add(jour, libelle);
      this.ajout.reset();
      return `${ajoute.label} ajouté.` + rescheduledMessage(ajoute.rescheduled);
    });
  }

  protected async ajouterPays(code: string, annee: number): Promise<void> {
    if (!Number.isInteger(annee)) {
      this.message.set({ texte: 'Saisissez une année.', erreur: true });
      return;
    }
    const nom = this.pays_().find((p) => p.code === code)?.label ?? code;
    this.aSaisir.set([]);
    await this.executer(async () => {
      const { added, alreadyPresent, manualDays, rescheduled } = await this.api.addNational(code, annee);
      this.aSaisir.set(manualDays);
      const ajoutes = added.length === 0 ? 'Aucun jour férié ajouté' : `${added.length} jour(s) férié(s) ajouté(s)`;
      return `${ajoutes} pour ${nom} ${annee}` + (alreadyPresent > 0 ? ` (${alreadyPresent} déjà présent(s)).` : '.')
        + rescheduledMessage(rescheduled);
    });
  }

  protected async retirer(jour: Holiday): Promise<void> {
    await this.executer(async () => {
      const rescheduled = await this.api.remove(jour.day);
      return `${jour.label} retiré.` + rescheduledMessage(rescheduled);
    });
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
      this.joursFeries.set(await this.api.list());
    } catch (error) {
      this.message.set({ texte: apiErrorMessage(error), erreur: true });
    }
  }
}
