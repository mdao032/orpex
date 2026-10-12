import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { Auth } from './auth';

/** Pages réservées aux utilisateurs connectés : sinon, écran de connexion puis retour à la page demandée. */
export const authGuard: CanActivateFn = (_route, state) => {
  if (inject(Auth).user()) {
    return true;
  }
  return inject(Router).createUrlTree(['/connexion'], { queryParams: { retour: state.url } });
};
