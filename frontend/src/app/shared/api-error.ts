import { HttpErrorResponse } from '@angular/common/http';

/** Message à afficher pour une erreur de l'API : le « detail » rédigé par le serveur (RFC 9457), sinon générique. */
export function apiErrorMessage(error: unknown): string {
  if (error instanceof HttpErrorResponse) {
    const detail = error.error?.detail;
    if (typeof detail === 'string' && detail.length > 0 && error.status < 500) {
      return detail;
    }
    if (error.status === 0 || error.status >= 500) {
      return 'Le service ne répond pas. Réessayez dans un instant.';
    }
  }
  return "L'opération n'a pas abouti.";
}
