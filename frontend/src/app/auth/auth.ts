import { HttpClient, HttpErrorResponse, HttpParameterCodec, HttpParams } from '@angular/common/http';
import { Injectable, computed, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';

export interface User {
  email: string;
  roles: string[];
  /** Mot de passe provisoire (choisi par un administrateur) : à remplacer avant tout autre usage. */
  mustChangePassword: boolean;
  /** Fuseau de l'installation (ex. Africa/Bamako) : les heures s'affichent dans ce fuseau. */
  timezone?: string;
}

/**
 * Encode tous les caractères spéciaux : l'encodeur par défaut d'Angular laisse passer « @ = ; : $ , / ? »,
 * alors qu'un mot de passe peut contenir n'importe quel caractère.
 */
const STRICT_FORM_ENCODING: HttpParameterCodec = {
  encodeKey: encodeURIComponent,
  encodeValue: encodeURIComponent,
  decodeKey: decodeURIComponent,
  decodeValue: decodeURIComponent,
};

/** Résultat d'une tentative de connexion, sans distinguer mauvais mot de passe, compte inconnu ou verrouillé. */
export type LoginResult = 'ok' | 'refused' | 'unavailable';

/**
 * Utilisateur connecté, d'après la session côté serveur (cookie).
 * Chaque appel à {@link refresh} fournit aussi le cookie XSRF-TOKEN qu'Angular renvoie sur les POST.
 */
@Injectable({ providedIn: 'root' })
export class Auth {
  private readonly http = inject(HttpClient);
  private readonly current = signal<User | null>(null);

  readonly user = this.current.asReadonly();
  readonly isAdmin = computed(() => this.current()?.roles.includes('ROLE_ADMIN') ?? false);

  /** Interroge le serveur : utilisateur connecté, ou null (401). */
  async refresh(): Promise<User | null> {
    try {
      this.current.set(await firstValueFrom(this.http.get<User>('/api/me')));
    } catch {
      this.current.set(null);
    }
    return this.current();
  }

  async login(email: string, password: string): Promise<LoginResult> {
    const body = new HttpParams({ encoder: STRICT_FORM_ENCODING }).set('username', email).set('password', password);
    try {
      await firstValueFrom(this.http.post('/login', body, { responseType: 'text' }));
    } catch (error) {
      return error instanceof HttpErrorResponse && error.status === 401 ? 'refused' : 'unavailable';
    }
    return (await this.refresh()) ? 'ok' : 'unavailable';
  }

  async logout(): Promise<void> {
    try {
      await firstValueFrom(this.http.post('/logout', null, { responseType: 'text' }));
    } finally {
      // La déconnexion efface aussi le jeton CSRF : refresh() en obtient un nouveau pour la prochaine connexion.
      await this.refresh();
    }
  }

  async changePassword(currentPassword: string, newPassword: string): Promise<void> {
    await firstValueFrom(this.http.put('/api/me/password', { currentPassword, newPassword }));
    await this.refresh();
  }

  /** La session a expiré côté serveur (401 sur une requête de l'API). */
  sessionExpired(): void {
    this.current.set(null);
  }
}
