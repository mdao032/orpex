import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { ActivatedRouteSnapshot, Router, RouterStateSnapshot, UrlTree, provideRouter } from '@angular/router';
import { Auth } from './auth';
import { authGuard } from './auth-guard';

describe('authGuard', () => {
  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()],
    });
  });

  const run = (url: string) =>
    TestBed.runInInjectionContext(() =>
      authGuard({} as ActivatedRouteSnapshot, { url } as RouterStateSnapshot),
    );

  it('sends an anonymous visitor to the login page, remembering where they were going', () => {
    const result = run('/import') as UrlTree;

    expect(TestBed.inject(Router).serializeUrl(result)).toBe('/connexion?retour=%2Fimport');
  });

  it('lets a logged-in user through', async () => {
    const refreshed = TestBed.inject(Auth).refresh();
    TestBed.inject(HttpTestingController).expectOne('/api/me').flush({ email: 'a@b.c', roles: [] });
    await refreshed;

    expect(run('/import')).toBe(true);
  });
});
