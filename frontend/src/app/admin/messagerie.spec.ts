import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { Messagerie } from './messagerie';

const nextTick = () => new Promise((resolve) => setTimeout(resolve));

const SAVED = {
  configured: true, host: 'relais.banque.ml', port: 587, encryption: 'STARTTLS', username: 'orpex',
  passwordSet: true, fromAddress: 'relances@banque.ml', fromName: 'Banque - Relances', caCertificate: null,
  encryptionKeyAvailable: true,
  health: { status: 'ERROR', checkedAt: '2026-10-14T08:00:00Z', detail: '535 5.7.8 authentication failed' },
};

describe('Messagerie', () => {
  let http: HttpTestingController;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [Messagerie],
      providers: [provideHttpClient(), provideHttpClientTesting()],
    }).compileComponents();
    http = TestBed.inject(HttpTestingController);
  });

  async function render(view: object) {
    const fixture = TestBed.createComponent(Messagerie);
    fixture.detectChanges();
    http.expectOne('/api/admin/smtp').flush(view);
    await nextTick();
    await fixture.whenStable();
    return { page: fixture.nativeElement as HTMLElement, stable: () => fixture.whenStable() };
  }

  it('never shows the saved password and keeps it when the field stays empty', async () => {
    const { page, stable } = await render(SAVED);
    const password = page.querySelector('input[type="password"]') as HTMLInputElement;
    expect(password.value).toBe('');
    expect(password.placeholder).toContain('Enregistré');

    page.querySelector('form')?.dispatchEvent(new Event('submit'));
    const save = http.expectOne((req) => req.method === 'PUT');
    expect(save.request.body).toEqual(expect.objectContaining({
      host: 'relais.banque.ml', port: 587, encryption: 'STARTTLS', username: 'orpex', password: '',
      removePassword: false,
    }));
    save.flush(SAVED);
    await nextTick();
    await stable();

    expect(page.querySelector('[role="status"]')?.textContent).toContain('Configuration enregistrée');
  });

  it('explains when the file configuration is in use and no encryption key exists', async () => {
    const { page } = await render({
      ...SAVED, configured: false, host: 'localhost', port: 1025, username: null, passwordSet: false,
      encryptionKeyAvailable: false,
    });

    expect(page.textContent).toContain('configuration du serveur (localhost:1025)');
    expect(page.querySelector('[role="alert"]')?.textContent).toContain('ORPEX_SECRET_KEY');
  });

  it('shows the relay health and sends a test mail', async () => {
    const { page, stable } = await render(SAVED);
    expect(page.textContent).toContain('535 5.7.8 authentication failed');

    const testButton = [...page.querySelectorAll('button')].find((b) => b.textContent?.includes('mail de test'));
    testButton?.click();
    http.expectOne('/api/admin/smtp/test').flush({ success: true, detail: null });
    await nextTick();
    http.expectOne((req) => req.method === 'GET').flush({ ...SAVED, health: { status: 'OK', checkedAt: null, detail: null } });
    await nextTick();
    await stable();

    expect(page.querySelector('[role="status"]')?.textContent).toContain('Mail de test envoyé');
  });
});
