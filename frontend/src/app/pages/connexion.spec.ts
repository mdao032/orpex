import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { Connexion } from './connexion';

describe('Connexion', () => {
  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [Connexion],
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()],
    }).compileComponents();
  });

  function submit(page: HTMLElement, email: string, password: string): void {
    const set = (selector: string, value: string) => {
      const input = page.querySelector(selector) as HTMLInputElement;
      input.value = value;
      input.dispatchEvent(new Event('input'));
    };
    set('#email', email);
    set('#mot-de-passe', password);
    page.querySelector('form')?.dispatchEvent(new Event('submit'));
  }

  it('shows a generic message when the credentials are refused', async () => {
    const fixture = TestBed.createComponent(Connexion);
    await fixture.whenStable();
    const page = fixture.nativeElement as HTMLElement;

    submit(page, 'admin@orpex.local', 'faux');
    TestBed.inject(HttpTestingController)
      .expectOne('/login')
      .flush('', { status: 401, statusText: 'Unauthorized' });
    await fixture.whenStable();

    expect(page.querySelector('[role="alert"]')?.textContent).toContain('Identifiants incorrects');
  });

  it('does not call the server when the form is incomplete', async () => {
    const fixture = TestBed.createComponent(Connexion);
    await fixture.whenStable();
    const page = fixture.nativeElement as HTMLElement;

    submit(page, '', '');
    await fixture.whenStable();

    TestBed.inject(HttpTestingController).expectNone('/login');
    expect(page.querySelector('[role="alert"]')?.textContent).toContain('Saisissez');
  });
});
