import { registerLocaleData } from '@angular/common';
import localeFr from '@angular/common/locales/fr';
import {
  ApplicationConfig,
  LOCALE_ID,
  inject,
  provideAppInitializer,
  provideBrowserGlobalErrorListeners,
} from '@angular/core';
import { provideHttpClient, withFetch, withInterceptors } from '@angular/common/http';
import { provideRouter } from '@angular/router';
import { routes } from './app.routes';
import { Auth } from './auth/auth';
import { sessionExpiredInterceptor } from './auth/session-expired-interceptor';

// Dates et nombres affichés à la française (données de locale incluses dans Angular, sans ressource externe).
registerLocaleData(localeFr);

export const appConfig: ApplicationConfig = {
  providers: [
    provideBrowserGlobalErrorListeners(),
    { provide: LOCALE_ID, useValue: 'fr' },
    provideHttpClient(withFetch(), withInterceptors([sessionExpiredInterceptor])),
    provideRouter(routes),
    // Avant la première navigation : savoir qui est connecté, et obtenir le cookie XSRF-TOKEN.
    provideAppInitializer(() => inject(Auth).refresh()),
  ],
};
