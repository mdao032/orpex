import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { ImportPreview } from '../imports/imports-api';
import { ImportFichier } from './import-fichier';

const nextTick = () => new Promise((resolve) => setTimeout(resolve));

const PREVIEW: ImportPreview = {
  fileName: 'relances.xlsx',
  missingColumns: [],
  counts: { toCreate: 1, toUpdate: 0, unchanged: 0, rejected: 1, withWarnings: 0, plannedSends: 5 },
  rows: [
    {
      rowNumber: 2, id: 'P-1', action: 'CREATE', errors: [], warnings: [],
      interpreted: {
        email: 'a@exemple.ml', firstName: 'Awa', lastName: '', caseReference: '', subject: 'Relance facture',
        sendMode: 'PERIODE', sendDate: null, dueDate: null, daysBefore: null, startDate: '2027-03-01',
        endDate: '2027-03-05', sendTime: '09:00:00', businessDaysOnly: true, dailyValidation: false, status: 'ACTIVE',
      },
      sends: { planned: 5, first: '2027-03-01', last: '2027-03-05', missedToday: 0 },
    },
    {
      rowNumber: 3, id: 'P-2', action: 'REJECTED', warnings: [], interpreted: null, sends: null,
      errors: ['date_envoi : date « 03/04 » illisible (format AAAA-MM-JJ attendu)'],
    },
  ],
};

describe('ImportFichier', () => {
  let http: HttpTestingController;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [ImportFichier],
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()],
    }).compileComponents();
    http = TestBed.inject(HttpTestingController);
  });

  async function render() {
    const fixture = TestBed.createComponent(ImportFichier);
    fixture.detectChanges();
    http.expectOne('/api/imports').flush([]);
    await nextTick();
    await fixture.whenStable();
    return { page: fixture.nativeElement as HTMLElement, stable: () => fixture.whenStable() };
  }

  function choose(page: HTMLElement): void {
    const input = page.querySelector('input[type="file"]') as HTMLInputElement;
    const transfer = { item: () => new File(['x'], 'relances.xlsx') } as unknown as FileList;
    Object.defineProperty(input, 'files', { value: transfer });
    input.dispatchEvent(new Event('change'));
  }

  it('links to the template and shows the preview line by line', async () => {
    const { page, stable } = await render();
    expect(page.querySelector('a[download]')?.getAttribute('href')).toBe('/api/imports/template');

    choose(page);
    const preview = http.expectOne('/api/imports/preview');
    expect(preview.request.body instanceof FormData).toBe(true);
    preview.flush(PREVIEW);
    await nextTick();
    await stable();

    const rows = page.querySelectorAll('tbody tr');
    expect(rows.length).toBe(2);
    expect(rows[0].textContent).toContain('Chaque jour du 01/03/2027 au 05/03/2027');
    expect(rows[0].textContent).toContain('5 envoi(s)');
    expect(rows[1].textContent).toContain('« 03/04 » illisible');
    expect(page.querySelector('.confirmation button')?.textContent).toContain('1 relance(s) à créer');
  });

  it('confirms with the same file and shows the result', async () => {
    const { page, stable } = await render();
    choose(page);
    http.expectOne('/api/imports/preview').flush(PREVIEW);
    await nextTick();
    await stable();

    (page.querySelector('.confirmation button') as HTMLButtonElement).click();
    const apply = http.expectOne((req) => req.method === 'POST' && req.url === '/api/imports');
    expect((apply.request.body as FormData).get('file')).toBeTruthy();
    apply.flush({ importId: 1, fileName: 'relances.xlsx', created: 1, unchanged: 0, notApplied: 0, rejected: 1 });
    await nextTick();
    http.expectOne((req) => req.method === 'GET' && req.url === '/api/imports').flush([]);
    await nextTick();
    await stable();

    expect(page.querySelector('.resultat')?.textContent).toContain('1 relance(s) créée(s)');
    expect(page.querySelector('tbody')).toBeNull();
  });

  it('explains a refused file', async () => {
    const { page, stable } = await render();
    choose(page);
    http.expectOne('/api/imports/preview').flush({ ...PREVIEW, missingColumns: ['validation_quotidienne'], rows: [] });
    await nextTick();
    await stable();

    expect(page.querySelector('[role="alert"]')?.textContent).toContain('validation_quotidienne');
    expect(page.querySelector('.confirmation')).toBeNull();
  });
});
