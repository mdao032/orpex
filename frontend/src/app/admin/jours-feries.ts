import { DatePipe } from '@angular/common';
import { Component, OnInit, inject, signal } from '@angular/core';
import { NonNullableFormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { apiErrorMessage } from '../shared/api-error';
import { Holiday, HolidaysApi } from './holidays-api';

@Component({
  selector: 'app-jours-feries',
  imports: [ReactiveFormsModule, DatePipe],
  styleUrl: './comptes.scss',
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
        Avec « jours ouvrés uniquement », aucun envoi n'est prévu un jour férié. Un changement s'applique aux
        calculs d'envois suivants.
      </p>
    </section>

    @if (message(); as m) {
      <p [class]="m.erreur ? 'erreur' : 'info'" [attr.role]="m.erreur ? 'alert' : 'status'">{{ m.texte }}</p>
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

  protected readonly ajout = inject(NonNullableFormBuilder).group({
    jour: ['', Validators.required],
    libelle: ['', Validators.required],
  });

  async ngOnInit(): Promise<void> {
    await this.recharger();
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
      return `${ajoute.label} ajouté.`;
    });
  }

  protected async retirer(jour: Holiday): Promise<void> {
    await this.executer(async () => {
      await this.api.remove(jour.day);
      return `${jour.label} retiré.`;
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
