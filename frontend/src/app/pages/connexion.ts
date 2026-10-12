import { Component, inject, signal } from '@angular/core';
import { NonNullableFormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { ActivatedRoute, Router } from '@angular/router';
import { Auth } from '../auth/auth';

@Component({
  selector: 'app-connexion',
  imports: [ReactiveFormsModule],
  styleUrl: './connexion.scss',
  template: `
    <section class="carte" aria-labelledby="titre-connexion">
      <h1 id="titre-connexion">Connexion</h1>

      @if (sessionExpiree) {
        <p class="info" role="status">Votre session a expiré. Reconnectez-vous pour continuer.</p>
      }

      <form [formGroup]="formulaire" (ngSubmit)="seConnecter()" novalidate>
        <label for="email">Adresse email</label>
        <input id="email" type="email" formControlName="email" autocomplete="username" required />

        <label for="mot-de-passe">Mot de passe</label>
        <input id="mot-de-passe" type="password" formControlName="motDePasse" autocomplete="current-password" required />

        @if (erreur()) {
          <p class="erreur" role="alert">{{ erreur() }}</p>
        }

        <button type="submit" [disabled]="enCours()">{{ enCours() ? 'Connexion…' : 'Se connecter' }}</button>
      </form>
    </section>
  `,
})
export class Connexion {
  private readonly auth = inject(Auth);
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);

  protected readonly formulaire = inject(NonNullableFormBuilder).group({
    email: ['', [Validators.required, Validators.email]],
    motDePasse: ['', Validators.required],
  });
  protected readonly erreur = signal<string | null>(null);
  protected readonly enCours = signal(false);
  protected readonly sessionExpiree = this.route.snapshot.queryParamMap.has('expiree');

  protected async seConnecter(): Promise<void> {
    if (this.formulaire.invalid) {
      this.erreur.set('Saisissez votre adresse email et votre mot de passe.');
      return;
    }
    this.enCours.set(true);
    this.erreur.set(null);
    const { email, motDePasse } = this.formulaire.getRawValue();
    const resultat = await this.auth.login(email, motDePasse);
    this.enCours.set(false);

    if (resultat === 'ok') {
      await this.router.navigateByUrl(this.pageDeRetour());
    } else if (resultat === 'refused') {
      this.formulaire.controls.motDePasse.reset();
      this.erreur.set(
        'Identifiants incorrects. Après plusieurs échecs, le compte est bloqué pendant quelques minutes.',
      );
    } else {
      this.erreur.set('Le service ne répond pas. Réessayez dans un instant.');
    }
  }

  /** Seules les adresses internes à l'application sont acceptées comme page de retour. */
  private pageDeRetour(): string {
    const retour = this.route.snapshot.queryParamMap.get('retour');
    return retour && retour.startsWith('/') && !retour.startsWith('//') && !retour.startsWith('/connexion')
      ? retour
      : '/lignes';
  }
}
