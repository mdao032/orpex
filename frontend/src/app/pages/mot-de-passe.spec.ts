import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { MotDePasse } from './mot-de-passe';

describe('MotDePasse', () => {
  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [MotDePasse],
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()],
    }).compileComponents();
  });

  async function submit(actuel: string, nouveau: string, confirmation: string) {
    const fixture = TestBed.createComponent(MotDePasse);
    await fixture.whenStable();
    const page = fixture.nativeElement as HTMLElement;
    for (const [id, value] of [['actuel', actuel], ['nouveau', nouveau], ['confirmation', confirmation]]) {
      const input = page.querySelector(`#${id}`) as HTMLInputElement;
      input.value = value;
      input.dispatchEvent(new Event('input'));
    }
    page.querySelector('form')?.dispatchEvent(new Event('submit'));
    await fixture.whenStable();
    return { page, stable: () => fixture.whenStable() };
  }

  it('refuses a confirmation that does not match, without calling the server', async () => {
    const { page } = await submit('ancien-mot-de-passe', 'nouveau-mot-de-passe', 'autre-chose-encore');

    TestBed.inject(HttpTestingController).expectNone('/api/me/password');
    expect(page.querySelector('[role="alert"]')?.textContent).toContain('ne correspond pas');
  });

  it('refuses a password shorter than 12 characters', async () => {
    const { page } = await submit('ancien-mot-de-passe', 'court', 'court');

    TestBed.inject(HttpTestingController).expectNone('/api/me/password');
    expect(page.querySelector('[role="alert"]')?.textContent).toContain('au moins 12 caractères');
  });

  it('shows the message written by the server when it refuses the change', async () => {
    const { page, stable } = await submit('faux-mot-de-passe', 'nouveau-mot-de-passe', 'nouveau-mot-de-passe');
    const request = TestBed.inject(HttpTestingController).expectOne('/api/me/password');
    expect(request.request.body).toEqual({ currentPassword: 'faux-mot-de-passe', newPassword: 'nouveau-mot-de-passe' });
    request.flush({ detail: 'Le mot de passe actuel est incorrect.' }, { status: 400, statusText: 'Bad Request' });
    await new Promise((resolve) => setTimeout(resolve));
    await stable();

    expect(page.querySelector('[role="alert"]')?.textContent).toContain('Le mot de passe actuel est incorrect.');
  });
});
