import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { ActivatedRouteSnapshot, Router, RouterStateSnapshot, UrlTree, provideRouter } from '@angular/router';
import { Auth } from './auth';
import { adminGuard, authGuard } from './auth-guard';

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
    TestBed.inject(HttpTestingController).expectOne('/api/me').flush({ email: 'a@b.c', roles: [], mustChangePassword: false });
    await refreshed;

    expect(run('/import')).toBe(true);
  });

  async function loginAs(user: object): Promise<void> {
    const refreshed = TestBed.inject(Auth).refresh();
    TestBed.inject(HttpTestingController).expectOne('/api/me').flush(user);
    await refreshed;
  }

  it('sends a user with a temporary password to the password page, and lets them stay there', async () => {
    await loginAs({ email: 'a@b.c', roles: ['ROLE_USER'], mustChangePassword: true });

    expect(TestBed.inject(Router).serializeUrl(run('/import') as UrlTree)).toBe('/mot-de-passe');
    expect(run('/mot-de-passe')).toBe(true);
  });

  it('keeps administration pages for administrators', async () => {
    await loginAs({ email: 'a@b.c', roles: ['ROLE_USER'], mustChangePassword: false });
    const refused = TestBed.runInInjectionContext(() =>
      adminGuard({} as ActivatedRouteSnapshot, { url: '/admin/comptes' } as RouterStateSnapshot),
    );
    expect(TestBed.inject(Router).serializeUrl(refused as UrlTree)).toBe('/lignes');

    await loginAs({ email: 'a@b.c', roles: ['ROLE_ADMIN'], mustChangePassword: false });
    const allowed = TestBed.runInInjectionContext(() =>
      adminGuard({} as ActivatedRouteSnapshot, { url: '/admin/comptes' } as RouterStateSnapshot),
    );
    expect(allowed).toBe(true);
  });
});
