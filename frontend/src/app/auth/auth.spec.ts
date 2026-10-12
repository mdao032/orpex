import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { Auth } from './auth';

const ADMIN = { email: 'admin@orpex.local', roles: ['ROLE_ADMIN'] };

/** Laisse s'exécuter les suites de promesses (requête suivante émise après un await). */
const nextTick = () => new Promise((resolve) => setTimeout(resolve));

describe('Auth', () => {
  let auth: Auth;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    auth = TestBed.inject(Auth);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('knows the logged-in user from /api/me', async () => {
    const result = auth.refresh();
    http.expectOne('/api/me').flush(ADMIN);

    expect(await result).toEqual(ADMIN);
    expect(auth.isAdmin()).toBe(true);
  });

  it('has no user when /api/me answers 401', async () => {
    const result = auth.refresh();
    http.expectOne('/api/me').flush(null, { status: 401, statusText: 'Unauthorized' });

    expect(await result).toBeNull();
    expect(auth.user()).toBeNull();
  });

  it('posts the credentials as a form, then loads the user', async () => {
    const result = auth.login('admin@orpex.local', 'a+b&c=d;é%');
    const login = http.expectOne('/login');
    expect(login.request.method).toBe('POST');
    expect(login.request.body.toString()).toBe('username=admin%40orpex.local&password=a%2Bb%26c%3Dd%3B%C3%A9%25');
    login.flush('');
    await nextTick();
    http.expectOne('/api/me').flush(ADMIN);

    expect(await result).toBe('ok');
    expect(auth.user()).toEqual(ADMIN);
  });

  it('reports refused credentials without loading the user', async () => {
    const result = auth.login('admin@orpex.local', 'faux');
    http.expectOne('/login').flush('', { status: 401, statusText: 'Unauthorized' });

    expect(await result).toBe('refused');
  });

  it('reports an unavailable service on other errors', async () => {
    const result = auth.login('admin@orpex.local', 'secret');
    http.expectOne('/login').flush('', { status: 503, statusText: 'Service Unavailable' });

    expect(await result).toBe('unavailable');
  });

  it('logs out, then asks the server again to get a fresh CSRF cookie', async () => {
    const result = auth.logout();
    http.expectOne('/logout').flush('');
    await nextTick();
    http.expectOne('/api/me').flush(null, { status: 401, statusText: 'Unauthorized' });
    await result;

    expect(auth.user()).toBeNull();
  });
});
