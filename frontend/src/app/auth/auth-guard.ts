import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { Auth } from './auth';

/**
 * Pages réservées aux utilisateurs connectés : sinon, écran de connexion puis retour à la page demandée.
 * Un mot de passe provisoire doit être remplacé avant d'accéder à toute autre page.
 */
export const authGuard: CanActivateFn = (_route, state) => {
  const user = inject(Auth).user();
  const router = inject(Router);
  if (!user) {
    return router.createUrlTree(['/connexion'], { queryParams: { retour: state.url } });
  }
  if (user.mustChangePassword && !state.url.startsWith('/mot-de-passe')) {
    return router.createUrlTree(['/mot-de-passe']);
  }
  return true;
};

/** Pages d'administration : réservées au rôle administrateur. */
export const adminGuard: CanActivateFn = () => inject(Auth).isAdmin() || inject(Router).createUrlTree(['/lignes']);
