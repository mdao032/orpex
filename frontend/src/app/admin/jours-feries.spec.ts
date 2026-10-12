import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { JoursFeries } from './jours-feries';

const nextTick = () => new Promise((resolve) => setTimeout(resolve));

describe('JoursFeries', () => {
  let http: HttpTestingController;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [JoursFeries],
      providers: [provideHttpClient(), provideHttpClientTesting()],
    }).compileComponents();
    http = TestBed.inject(HttpTestingController);
  });

  async function render(holidays: object[]) {
    const fixture = TestBed.createComponent(JoursFeries);
    fixture.detectChanges();
    http.expectOne('/api/admin/holidays').flush(holidays);
    await nextTick();
    http.expectOne('/api/admin/holidays/countries').flush([
      { code: 'FRANCE', label: 'France métropolitaine', manualDays: [] },
      { code: 'MALI', label: 'Mali', manualDays: ['Korité (fin du Ramadan)', 'Tabaski'] },
    ]);
    await nextTick();
    await fixture.whenStable();
    return { page: fixture.nativeElement as HTMLElement, stable: () => fixture.whenStable() };
  }

  it('says that only weekends are non-working days when there is no holiday', async () => {
    const { page } = await render([]);

    expect(page.textContent).toContain('seuls les samedis et dimanches');
    expect(page.querySelector('table')).toBeNull();
  });

  it('adds a holiday, then reloads the list', async () => {
    const { page, stable } = await render([]);
    const set = (selector: string, value: string) => {
      const field = page.querySelector(selector) as HTMLInputElement;
      field.value = value;
      field.dispatchEvent(new Event('input'));
    };
    set('input[formControlName="jour"]', '2026-12-25');
    set('input[formControlName="libelle"]', 'Noël');
    page.querySelector('form')?.dispatchEvent(new Event('submit'));

    const add = http.expectOne((req) => req.method === 'POST' && req.url === '/api/admin/holidays');
    expect(add.request.body).toEqual({ day: '2026-12-25', label: 'Noël' });
    add.flush({ day: '2026-12-25', label: 'Noël' });
    await nextTick();
    http.expectOne((req) => req.method === 'GET').flush([{ day: '2026-12-25', label: 'Noël' }]);
    await nextTick();
    await stable();

    expect(page.querySelectorAll('tbody tr').length).toBe(1);
    expect(page.querySelector('[role="status"]')?.textContent).toContain('Noël ajouté.');
  });

  it('shows the server message when removal fails', async () => {
    const { page, stable } = await render([{ day: '2026-12-25', label: 'Noël' }]);

    (page.querySelector('tbody button') as HTMLButtonElement).click();
    http.expectOne('/api/admin/holidays/2026-12-25').flush(
      { detail: "Le 25/12/2026 n'est pas un jour férié." },
      { status: 404, statusText: 'Not Found' },
    );
    await nextTick();
    await stable();

    expect(page.querySelector('[role="alert"]')?.textContent).toContain("n'est pas un jour férié");
  });

  it('adds the holidays of the chosen country and year, and lists those to enter by hand', async () => {
    const { page, stable } = await render([]);
    await stable();
    const form = page.querySelectorAll('form')[1];
    (form.querySelector('select') as HTMLSelectElement).value = 'MALI';
    (form.querySelector('input') as HTMLInputElement).value = '2028';
    form.dispatchEvent(new Event('submit'));

    http.expectOne('/api/admin/holidays/national/MALI/2028').flush({
      added: [{ day: '2028-01-20', label: "Fête de l'Armée" }],
      alreadyPresent: 7,
      manualDays: ['Korité (fin du Ramadan)', 'Tabaski'],
    });
    await nextTick();
    http.expectOne((req) => req.method === 'GET').flush([{ day: '2028-01-20', label: "Fête de l'Armée" }]);
    await nextTick();
    await stable();

    expect(page.querySelector('[role="status"]')?.textContent).toContain(
      '1 jour(s) férié(s) ajouté(s) pour Mali 2028 (7 déjà présent(s)).',
    );
    expect(page.querySelector('[role="note"]')?.textContent).toContain('Korité (fin du Ramadan), Tabaski');
  });
});
