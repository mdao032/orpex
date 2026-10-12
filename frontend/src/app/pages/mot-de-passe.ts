import { Component, inject, signal } from '@angular/core';
import { NonNullableFormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { Router } from '@angular/router';
import { Auth } from '../auth/auth';
import { apiErrorMessage } from '../shared/api-error';

/** Même règle que le serveur (orpex.password.min-length) ; le serveur reste seul juge. */
const MIN_LENGTH = 12;

@Component({
  selector: 'app-mot-de-passe',
  imports: [ReactiveFormsModule],
  styleUrl: './connexion.scss',
  template: `
    <section class="carte" aria-labelledby="titre-mot-de-passe">
      <h1 id="titre-mot-de-passe">Changer mon mot de passe</h1>

      @if (auth.user()?.mustChangePassword) {
        <p class="info" role="status">
          Votre mot de passe actuel est provisoire : choisissez-en un nouveau pour continuer.
        </p>
      }

      <form [formGroup]="formulaire" (ngSubmit)="enregistrer()" novalidate>
        <label for="actuel">Mot de passe actuel</label>
        <input id="actuel" type="password" formControlName="actuel" autocomplete="current-password" required />

        <label for="nouveau">Nouveau mot de passe</label>
        <input id="nouveau" type="password" formControlName="nouveau" autocomplete="new-password" required
               aria-describedby="aide-nouveau" />
        <small id="aide-nouveau">Au moins {{ minLength }} caractères. Une phrase facile à retenir convient bien.</small>

        <label for="confirmation">Confirmation du nouveau mot de passe</label>
        <input id="confirmation" type="password" formControlName="confirmation" autocomplete="new-password" required />

        @if (erreur()) {
          <p class="erreur" role="alert">{{ erreur() }}</p>
        }
        @if (succes()) {
          <p class="info" role="status">Votre mot de passe a été changé.</p>
        }

        <button type="submit" [disabled]="enCours()">{{ enCours() ? 'Enregistrement…' : 'Enregistrer' }}</button>
      </form>
    </section>
  `,
})
export class MotDePasse {
  protected readonly auth = inject(Auth);
  private readonly router = inject(Router);

  protected readonly minLength = MIN_LENGTH;
  protected readonly formulaire = inject(NonNullableFormBuilder).group({
    actuel: ['', Validators.required],
    nouveau: ['', Validators.required],
    confirmation: ['', Validators.required],
  });
  protected readonly erreur = signal<string | null>(null);
  protected readonly succes = signal(false);
  protected readonly enCours = signal(false);

  protected async enregistrer(): Promise<void> {
    const { actuel, nouveau, confirmation } = this.formulaire.getRawValue();
    this.succes.set(false);
    if (this.formulaire.invalid) {
      this.erreur.set('Remplissez les trois champs.');
      return;
    }
    if (nouveau !== confirmation) {
      this.erreur.set('La confirmation ne correspond pas au nouveau mot de passe.');
      return;
    }
    if ([...nouveau].length < MIN_LENGTH) {
      this.erreur.set(`Le mot de passe doit contenir au moins ${MIN_LENGTH} caractères.`);
      return;
    }
    const etaitProvisoire = this.auth.user()?.mustChangePassword ?? false;
    this.enCours.set(true);
    this.erreur.set(null);
    try {
      await this.auth.changePassword(actuel, nouveau);
      this.formulaire.reset();
      if (etaitProvisoire) {
        await this.router.navigateByUrl('/lignes');
      } else {
        this.succes.set(true);
      }
    } catch (error) {
      this.erreur.set(apiErrorMessage(error));
    } finally {
      this.enCours.set(false);
    }
  }
}
