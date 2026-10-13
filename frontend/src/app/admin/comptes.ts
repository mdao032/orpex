import { DatePipe } from '@angular/common';
import { Component, OnInit, inject, signal } from '@angular/core';
import { NonNullableFormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { Auth } from '../auth/auth';
import { apiErrorMessage } from '../shared/api-error';
import { Account, AccountsApi, Role } from './accounts-api';

@Component({
  selector: 'app-comptes',
  imports: [ReactiveFormsModule, DatePipe],
  styleUrl: '../shared/page.scss',
  template: `
    <h1>Comptes</h1>

    <section class="bloc" aria-labelledby="titre-creation">
      <h2 id="titre-creation">Nouveau compte</h2>
      <form class="creation" [formGroup]="creation" (ngSubmit)="creer()" novalidate>
        <label>Adresse email
          <input type="email" formControlName="email" autocomplete="off" required />
        </label>
        <label>Rôle
          <select formControlName="role">
            <option value="USER">Utilisateur</option>
            <option value="ADMIN">Administrateur</option>
          </select>
        </label>
        <label>Mot de passe provisoire
          <input type="text" formControlName="motDePasse" autocomplete="off" required />
        </label>
        <button type="submit" [disabled]="enCours()">Créer</button>
      </form>
      <p class="aide">
        Transmettez le mot de passe provisoire à la personne : elle devra le remplacer à sa première connexion.
      </p>
    </section>

    @if (message(); as m) {
      <p [class]="m.erreur ? 'erreur' : 'info'" [attr.role]="m.erreur ? 'alert' : 'status'">{{ m.texte }}</p>
    }

    <table>
      <thead>
        <tr>
          <th scope="col">Email</th>
          <th scope="col">Rôle</th>
          <th scope="col">État</th>
          <th scope="col">Dernière connexion</th>
          <th scope="col">Créé le</th>
          <th scope="col"><span class="masque">Actions</span></th>
        </tr>
      </thead>
      <tbody>
        @for (compte of comptes(); track compte.id) {
          <tr [class.inactif]="!compte.enabled">
            <td>{{ compte.email }}</td>
            <td>{{ compte.role === 'ADMIN' ? 'Administrateur' : 'Utilisateur' }}</td>
            <td>{{ etat(compte) }}</td>
            <td>{{ compte.lastLoginAt ? (compte.lastLoginAt | date: 'dd/MM/yyyy HH:mm') : '—' }}</td>
            <td>{{ compte.createdAt | date: 'dd/MM/yyyy' }}</td>
            <td class="actions">
              @if (reinitialisation() === compte.id) {
                <form (submit)="reinitialiser(compte, provisoire.value); $event.preventDefault()">
                  <input #provisoire type="text" aria-label="Nouveau mot de passe provisoire"
                         placeholder="Mot de passe provisoire" autocomplete="off" />
                  <button type="submit" [disabled]="enCours()">Valider</button>
                  <button type="button" (click)="reinitialisation.set(null)">Annuler</button>
                </form>
              } @else {
                <button type="button" (click)="reinitialisation.set(compte.id)">Réinitialiser le mot de passe</button>
                @if (compte.email !== moi()) {
                  <button type="button" (click)="basculerRole(compte)" [disabled]="enCours()">
                    {{ compte.role === 'ADMIN' ? 'Passer utilisateur' : 'Passer administrateur' }}
                  </button>
                  <button type="button" (click)="basculerActivation(compte)" [disabled]="enCours()">
                    {{ compte.enabled ? 'Désactiver' : 'Réactiver' }}
                  </button>
                }
              }
            </td>
          </tr>
        }
      </tbody>
    </table>
  `,
})
export class Comptes implements OnInit {
  private readonly api = inject(AccountsApi);
  private readonly auth = inject(Auth);

  protected readonly comptes = signal<Account[]>([]);
  protected readonly message = signal<{ texte: string; erreur: boolean } | null>(null);
  protected readonly enCours = signal(false);
  protected readonly reinitialisation = signal<number | null>(null);
  protected readonly moi = () => this.auth.user()?.email;

  protected readonly creation = inject(NonNullableFormBuilder).group({
    email: ['', [Validators.required, Validators.email]],
    role: ['USER' as Role],
    motDePasse: ['', Validators.required],
  });

  async ngOnInit(): Promise<void> {
    await this.recharger();
  }

  protected etat(compte: Account): string {
    if (!compte.enabled) {
      return 'Désactivé';
    }
    if (compte.locked) {
      return 'Verrouillé (échecs de connexion)';
    }
    return compte.mustChangePassword ? 'Mot de passe provisoire' : 'Actif';
  }

  protected async creer(): Promise<void> {
    if (this.creation.invalid) {
      this.message.set({ texte: 'Saisissez une adresse email valide et un mot de passe provisoire.', erreur: true });
      return;
    }
    const { email, role, motDePasse } = this.creation.getRawValue();
    await this.executer(async () => {
      const cree = await this.api.create(email, role, motDePasse);
      this.creation.reset();
      return `Compte ${cree.email} créé.`;
    });
  }

  protected async basculerActivation(compte: Account): Promise<void> {
    if (compte.enabled && !confirm(`Désactiver le compte ${compte.email} ? La personne sera déconnectée.`)) {
      return;
    }
    await this.executer(async () => {
      const modifie = compte.enabled ? await this.api.disable(compte.id) : await this.api.enable(compte.id);
      return `Compte ${modifie.email} ${modifie.enabled ? 'réactivé' : 'désactivé'}.`;
    });
  }

  protected async basculerRole(compte: Account): Promise<void> {
    const role: Role = compte.role === 'ADMIN' ? 'USER' : 'ADMIN';
    await this.executer(async () => {
      const modifie = await this.api.changeRole(compte.id, role);
      return `${modifie.email} est maintenant ${role === 'ADMIN' ? 'administrateur' : 'utilisateur'}.`;
    });
  }

  protected async reinitialiser(compte: Account, motDePasse: string): Promise<void> {
    await this.executer(async () => {
      await this.api.resetPassword(compte.id, motDePasse);
      this.reinitialisation.set(null);
      return `Mot de passe provisoire défini pour ${compte.email}.`;
    });
  }

  /** Exécute une action, affiche son résultat (ou l'erreur rédigée par le serveur), puis recharge la liste. */
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
      this.comptes.set(await this.api.list());
    } catch (error) {
      this.message.set({ texte: apiErrorMessage(error), erreur: true });
    }
  }
}
