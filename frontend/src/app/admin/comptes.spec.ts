import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { Account } from './accounts-api';
import { Comptes } from './comptes';

const ACCOUNTS: Account[] = [
  {
    id: 1, email: 'admin@orpex.local', role: 'ADMIN', enabled: true, locked: false, mustChangePassword: false,
    lastLoginAt: '2026-10-13T08:00:00Z', createdAt: '2026-10-12T08:00:00Z',
  },
  {
    id: 2, email: 'parti@orpex.local', role: 'USER', enabled: false, locked: false, mustChangePassword: false,
    lastLoginAt: null, createdAt: '2026-10-12T09:00:00Z',
  },
];

const nextTick = () => new Promise((resolve) => setTimeout(resolve));

describe('Comptes', () => {
  let http: HttpTestingController;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [Comptes],
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()],
    }).compileComponents();
    http = TestBed.inject(HttpTestingController);
  });

  async function render(): Promise<{ page: HTMLElement; stable: () => Promise<unknown> }> {
    const fixture = TestBed.createComponent(Comptes);
    fixture.detectChanges();
    http.expectOne('/api/admin/accounts').flush(ACCOUNTS);
    await nextTick();
    await fixture.whenStable();
    return { page: fixture.nativeElement as HTMLElement, stable: () => fixture.whenStable() };
  }

  it('lists the accounts with their role and state', async () => {
    const { page } = await render();
    const rows = page.querySelectorAll('tbody tr');

    expect(rows.length).toBe(2);
    expect(rows[0].textContent).toContain('Administrateur');
    expect(rows[1].textContent).toContain('Désactivé');
    expect(rows[1].textContent).toContain('Réactiver');
  });

  it('creates an account with a temporary password and shows the server refusal', async () => {
    const { page, stable } = await render();
    const set = (selector: string, value: string) => {
      const field = page.querySelector(selector) as HTMLInputElement;
      field.value = value;
      field.dispatchEvent(new Event('input'));
    };
    set('input[formControlName="email"]', 'nouveau@orpex.local');
    set('input[formControlName="motDePasse"]', 'provisoire-123456');
    page.querySelector('form.creation')?.dispatchEvent(new Event('submit'));

    const request = http.expectOne('/api/admin/accounts');
    expect(request.request.method).toBe('POST');
    expect(request.request.body).toEqual({
      email: 'nouveau@orpex.local', role: 'USER', temporaryPassword: 'provisoire-123456',
    });
    request.flush(
      { detail: "Un compte existe déjà avec l'adresse nouveau@orpex.local." },
      { status: 409, statusText: 'Conflict' },
    );
    await nextTick();
    await stable();

    expect(page.querySelector('[role="alert"]')?.textContent).toContain('existe déjà');
  });
});
