import { HttpErrorResponse, HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { Router } from '@angular/router';
import { catchError, throwError } from 'rxjs';
import { Auth } from './auth';

/**
 * Un 401 sur l'API pendant l'utilisation signifie que la session a expiré (30 min d'inactivité) ou que le compte a
 * été désactivé : retour à l'écran de connexion. /api/me et /login gèrent eux-mêmes leur 401.
 * Un 403 PASSWORD_CHANGE_REQUIRED renvoie vers le changement de mot de passe.
 */
export const sessionExpiredInterceptor: HttpInterceptorFn = (req, next) => {
  const auth = inject(Auth);
  const router = inject(Router);
  return next(req).pipe(
    catchError((error: unknown) => {
      if (error instanceof HttpErrorResponse && error.status === 401 && isApiCallNeedingSession(req.url)) {
        auth.sessionExpired();
        router.navigate(['/connexion'], { queryParams: { retour: router.url, expiree: 1 } });
      } else if (error instanceof HttpErrorResponse && error.status === 403
          && error.error?.code === 'PASSWORD_CHANGE_REQUIRED') {
        router.navigate(['/mot-de-passe']);
      }
      return throwError(() => error);
    }),
  );
};

function isApiCallNeedingSession(url: string): boolean {
  return url.startsWith('/api/') && url !== '/api/me';
}
