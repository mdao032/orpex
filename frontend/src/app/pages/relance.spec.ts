import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap, provideRouter } from '@angular/router';
import { RuleDetail } from '../imports/rules-api';
import { Relance } from './relance';

const nextTick = () => new Promise((resolve) => setTimeout(resolve));

const DETAIL: RuleDetail = {
  rule: {
    id: 7, businessId: 'P-7', recipientEmail: 'a@exemple.ml', firstName: 'Awa', lastName: 'Traoré',
    caseReference: 'FAC-1', subject: 'Relance facture', sendMode: 'PERIODE', sendDate: null, dueDate: null,
    daysBefore: null, startDate: '2027-06-07', endDate: '2027-06-11', sendTime: '09:00:00', businessDaysOnly: true,
    dailyValidation: false, status: 'ACTIVE', paused: false, activatedAt: '2026-10-14T08:00:00Z',
    updatedAt: '2026-10-14T08:00:00Z', forecast: null,
  },
  body: 'Bonjour {{prenom}},\nmerci de régler la facture.',
  sends: [
    { id: 1, scheduledDate: '2027-06-07', scheduledAt: '2027-06-07T09:00:00Z', state: 'MISSED',
      validatedAt: null, sentAt: null, attempts: 0, lastError: null, nextAttemptAt: null, issue: null },
    { id: 2, scheduledDate: '2027-06-08', scheduledAt: '2027-06-08T09:00:00Z', state: 'PLANNED',
      validatedAt: null, sentAt: null, attempts: 0, lastError: null, nextAttemptAt: null, issue: null },
    { id: 3, scheduledDate: '2027-06-09', scheduledAt: '2027-06-09T09:00:00Z', state: 'PLANNED',
      validatedAt: null, sentAt: null, attempts: 1,
      lastError: 'serveur de messagerie injoignable : Connection refused: getsockopt',
      nextAttemptAt: '2027-06-09T09:01:00Z', issue: 'RELAY_UNREACHABLE' },
  ],
  countsByState: { MISSED: 1, PLANNED: 2 },
  maxAttempts: 4,
};

describe('Relance', () => {
  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [Relance],
      providers: [
        provideRouter([]),
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: ActivatedRoute, useValue: { snapshot: { paramMap: convertToParamMap({ id: '7' }) } } },
      ],
    }).compileComponents();
  });

  async function render(): Promise<HTMLElement> {
    const fixture = TestBed.createComponent(Relance);
    fixture.detectChanges();
    TestBed.inject(HttpTestingController).expectOne('/api/rules/7').flush(DETAIL);
    await nextTick();
    await fixture.whenStable();
    return fixture.nativeElement as HTMLElement;
  }

  it('shows the message, the schedule and every send with its state in French', async () => {
    const page = await render();

    expect(page.querySelector('h1')?.textContent).toContain('Relance P-7');
    expect(page.querySelector('h1')?.textContent).toContain('Active');
    expect(page.querySelector('.contenu')?.textContent).toContain('merci de régler la facture');
    expect(page.textContent).toContain('Chaque jour du 07/06/2027 au 11/06/2027');
    const rows = page.querySelectorAll('tbody tr');
    expect(rows.length).toBe(3);
    expect(rows[0].textContent).toContain('07/06/2027');
    expect(rows[0].textContent).toContain('Manqué');
    expect(rows[1].textContent).toContain('Prévu');
    const problem = rows[2].querySelector('.probleme')?.textContent ?? '';
    expect(problem).toContain('Le serveur de messagerie ne répond pas. Nouvel essai automatique le 09/06/2027');
    expect(problem).toContain('(essai 2 sur 4)');
    expect(problem).not.toContain('getsockopt');
    expect(rows[2].querySelector('details.technique')?.textContent).toContain('getsockopt');
    const counters = page.querySelector('.compteurs')?.textContent ?? '';
    expect(counters).toContain('0 envoyé(s)');
    expect(counters).toContain('2 à venir');
    expect(counters).toContain('1 manqué');
  });

  it('offers the actions of an activated rule', async () => {
    const page = await render();
    const labels = [...page.querySelectorAll('.actions button')].map((b) => b.textContent?.trim());

    expect(labels).toEqual(["M'envoyer cette relance", 'Mettre en pause', 'Clore ce dossier']);
  });

  it('lets the owner decide about a send interrupted by a stop', async () => {
    const fixture = TestBed.createComponent(Relance);
    fixture.detectChanges();
    const http = TestBed.inject(HttpTestingController);
    const toCheck = { ...DETAIL, sends: [{ ...DETAIL.sends[1], state: 'TO_CHECK' as const,
      lastError: "envoi interrompu (arrêt de l'application)", issue: 'INTERRUPTED' as const }],
      countsByState: { TO_CHECK: 1 } };
    http.expectOne('/api/rules/7').flush(toCheck);
    await nextTick();
    await fixture.whenStable();
    const page = fixture.nativeElement as HTMLElement;
    expect(page.querySelector('tbody tr')?.textContent).toContain('À vérifier');
    const confirmSpy = vi.spyOn(window, 'confirm').mockReturnValue(true);

    (page.querySelector('.verification button') as HTMLButtonElement).click();
    const resolve = http.expectOne('/api/rules/7/sends/2/resolve');
    expect(resolve.request.body).toEqual({ sent: true });
    resolve.flush({ ...toCheck.sends[0], state: 'SENT' });
    await nextTick();
    http.expectOne('/api/rules/7').flush({ ...toCheck, sends: [{ ...toCheck.sends[0], state: 'SENT' }],
      countsByState: { SENT: 1 } });
    await nextTick();
    await fixture.whenStable();

    expect(page.querySelector('[role="status"]')?.textContent).toContain('Envoi marqué « Envoyé ».');
    expect(page.querySelector('.verification')).toBeNull();
    confirmSpy.mockRestore();
  });
});
