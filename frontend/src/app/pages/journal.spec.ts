import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { Auth } from '../auth/auth';
import { AuditView } from '../audit/audit-api';
import { Journal } from './journal';

const nextTick = () => new Promise((resolve) => setTimeout(resolve));

const ENTRIES: AuditView[] = [
  {
    id: 2, occurredAt: '2026-10-16T09:00:05Z', actor: null, event: 'SEND_STATE_CHANGED', eventLabel: 'Envoi',
    objectType: 'OCCURRENCE', objectId: '41', before: 'Prévu',
    after: 'P-1, a@exemple.ml, prévu le 16/10/2026 09:00 : Envoyé le 16/10/2026 09:00',
  },
  {
    id: 1, occurredAt: '2026-10-16T08:00:00Z', actor: 'moi@test.local', event: 'RULE_UPDATED',
    eventLabel: 'Relance modifiée', objectType: 'RULE', objectId: 'P-1', before: 'heure_envoi : 08:00',
    after: 'heure_envoi : 09:00',
  },
];

describe('Journal', () => {
  let http: HttpTestingController;
  let admin = false;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [Journal],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: Auth, useValue: { isAdmin: () => admin, user: () => ({ timezone: 'Africa/Bamako' }) } },
      ],
    }).compileComponents();
    http = TestBed.inject(HttpTestingController);
  });

  async function render() {
    const fixture = TestBed.createComponent(Journal);
    fixture.detectChanges();
    return { fixture, page: fixture.nativeElement as HTMLElement };
  }

  it('lists the events with their author and links the CSV export', async () => {
    admin = false;
    const { fixture, page } = await render();
    http.expectOne('/api/audit').flush(ENTRIES);
    await nextTick();
    await fixture.whenStable();

    const rows = page.querySelectorAll('tbody tr');
    expect(rows.length).toBe(2);
    expect(rows[0].textContent).toContain('système');
    expect(rows[0].textContent).toContain('Envoyé le 16/10/2026 09:00');
    expect(rows[1].textContent).toContain('moi@test.local');
    expect(rows[1].textContent).toContain('heure_envoi : 08:00');
    expect(page.querySelector('a[download]')?.getAttribute('href')).toBe('/api/audit/export');
    expect(page.textContent).not.toContain('Tout le journal');
  });

  it('lets an admin read the whole log for a period', async () => {
    admin = true;
    const { fixture, page } = await render();
    http.expectOne('/api/audit').flush([]);
    await nextTick();
    await fixture.whenStable();

    const [from, to] = Array.from(page.querySelectorAll('input[type="date"]')) as HTMLInputElement[];
    from.value = '2026-10-01';
    from.dispatchEvent(new Event('input'));
    to.value = '2026-10-31';
    to.dispatchEvent(new Event('input'));
    (page.querySelector('input[type="checkbox"]') as HTMLInputElement).click();
    const request = http.expectOne((r) => r.url === '/api/admin/audit');
    expect(request.request.params.get('from')).toBe('2026-10-01');
    expect(request.request.params.get('to')).toBe('2026-10-31');
    request.flush([]);
    await nextTick();
    await fixture.whenStable();

    expect(page.querySelector('a[download]')?.getAttribute('href'))
      .toBe('/api/admin/audit/export?from=2026-10-01&to=2026-10-31');
    expect(page.textContent).toContain('Aucun événement sur cette période.');
  });
});
