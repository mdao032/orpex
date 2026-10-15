import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { vi } from 'vitest';
import { RuleDetail } from '../imports/rules-api';
import { RelanceModification, VALIDATION_DROP_QUESTION, editMessage } from './relance-modification';

const nextTick = () => new Promise((resolve) => setTimeout(resolve));

const DETAIL: RuleDetail = {
  rule: {
    id: 7, businessId: 'P-7', recipientEmail: 'a@exemple.ml', firstName: 'Awa', lastName: null,
    caseReference: null, subject: 'Relance facture', sendMode: 'PERIODE', sendDate: null, dueDate: null,
    daysBefore: null, startDate: '2027-06-07', endDate: '2027-06-11', sendTime: '09:00:00', businessDaysOnly: true,
    dailyValidation: true, status: 'ACTIVE', paused: false, activatedAt: '2026-10-14T08:00:00Z',
    updatedAt: '2026-10-14T08:00:00Z', forecast: null,
  },
  body: 'Bonjour {{prenom}}',
  sends: [],
  countsByState: {},
  maxAttempts: 4,
};

describe('RelanceModification', () => {
  let http: HttpTestingController;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [RelanceModification],
      providers: [provideHttpClient(), provideHttpClientTesting()],
    }).compileComponents();
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => vi.restoreAllMocks());

  async function render() {
    const fixture = TestBed.createComponent(RelanceModification);
    fixture.componentRef.setInput('detail', DETAIL);
    const messages: string[] = [];
    fixture.componentInstance.enregistre.subscribe((m) => messages.push(m));
    fixture.detectChanges();
    await fixture.whenStable();
    const page = fixture.nativeElement as HTMLElement;
    return { page, messages, stable: () => fixture.whenStable() };
  }

  function type(page: HTMLElement, selector: string, value: string): void {
    const input = page.querySelector(selector) as HTMLInputElement;
    input.value = value;
    input.dispatchEvent(new Event('input'));
  }

  it('is prefilled and sends the new configuration', async () => {
    const { page, messages, stable } = await render();
    expect((page.querySelector('input[type="email"]') as HTMLInputElement).value).toBe('a@exemple.ml');
    expect((page.querySelector('input[type="time"]') as HTMLInputElement).value).toBe('09:00');

    type(page, 'input[type="time"]', '10:30');
    (page.querySelector('button[type="submit"]') as HTMLButtonElement).click();
    const request = http.expectOne((req) => req.method === 'PUT' && req.url === '/api/rules/7');
    expect(request.request.body).toMatchObject({
      sendTime: '10:30', startDate: '2027-06-07', dailyValidation: true, confirmValidationDrop: false,
    });
    request.flush({
      changes: [{ field: 'heure_envoi', before: '09:00', after: '10:30' }],
      sendsAdded: 0, sendsMoved: 5, sendsCancelled: 0,
    });
    await nextTick();
    await stable();

    expect(messages).toEqual(['Relance modifiée (heure_envoi) ; envois à venir : 5 déplacé(s).']);
  });

  it('asks for a confirmation before dropping the daily validation', async () => {
    const { page, stable } = await render();
    const ask = vi.spyOn(window, 'confirm').mockReturnValue(false);
    (page.querySelectorAll('input[type="checkbox"]')[1] as HTMLInputElement).click();
    await stable();

    (page.querySelector('button[type="submit"]') as HTMLButtonElement).click();
    expect(ask).toHaveBeenCalledWith(VALIDATION_DROP_QUESTION);
    http.expectNone('/api/rules/7');

    ask.mockReturnValue(true);
    (page.querySelector('button[type="submit"]') as HTMLButtonElement).click();
    const request = http.expectOne('/api/rules/7');
    expect(request.request.body).toMatchObject({ dailyValidation: false, confirmValidationDrop: true });
  });

  it('shows why the server refused the change', async () => {
    const { page, stable } = await render();
    (page.querySelector('button[type="submit"]') as HTMLButtonElement).click();
    http.expectOne('/api/rules/7').flush(
      { detail: 'Modification refusée : date_fin : doit être égale ou postérieure à date_debut.' },
      { status: 400, statusText: 'Bad Request' },
    );
    await nextTick();
    await stable();

    expect(page.querySelector('[role="alert"]')?.textContent).toContain('date_fin');
  });

  it('says when nothing changed', () => {
    expect(editMessage({ changes: [], sendsAdded: 0, sendsMoved: 0, sendsCancelled: 0 })).toBe('Aucune modification.');
  });
});
