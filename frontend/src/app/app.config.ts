import { ApplicationConfig, inject, provideAppInitializer, provideBrowserGlobalErrorListeners } from '@angular/core';
import { provideHttpClient, withFetch, withInterceptors } from '@angular/common/http';
import { provideRouter } from '@angular/router';
import { routes } from './app.routes';
import { Auth } from './auth/auth';
import { sessionExpiredInterceptor } from './auth/session-expired-interceptor';

export const appConfig: ApplicationConfig = {
  providers: [
    provideBrowserGlobalErrorListeners(),
    provideHttpClient(withFetch(), withInterceptors([sessionExpiredInterceptor])),
    provideRouter(routes),
    // Avant la première navigation : savoir qui est connecté, et obtenir le cookie XSRF-TOKEN.
    provideAppInitializer(() => inject(Auth).refresh()),
  ],
};
