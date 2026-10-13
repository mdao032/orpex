import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { Lignes } from './lignes';

const nextTick = () => new Promise((resolve) => setTimeout(resolve));

describe('Lignes', () => {
  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [Lignes],
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()],
    }).compileComponents();
  });

  async function render(rules: object[]): Promise<HTMLElement> {
    const fixture = TestBed.createComponent(Lignes);
    fixture.detectChanges();
    TestBed.inject(HttpTestingController).expectOne('/api/rules').flush(rules);
    await nextTick();
    await fixture.whenStable();
    return fixture.nativeElement as HTMLElement;
  }

  it('invites to import a file when there is no rule', async () => {
    const page = await render([]);

    expect(page.querySelector('a')?.getAttribute('href')).toBe('/import');
  });

  it('lists the rules with their schedule', async () => {
    const page = await render([{
      id: 1, businessId: 'P-1', recipientEmail: 'a@exemple.ml', firstName: 'Awa', lastName: null,
      caseReference: 'FAC-1', subject: 'Relance facture', sendMode: 'PERIODE', sendDate: null, dueDate: null,
      daysBefore: null, startDate: '2027-03-01', endDate: '2027-03-05', sendTime: '09:00:00',
      businessDaysOnly: true, dailyValidation: true, status: 'ACTIVE', paused: false, updatedAt: '2026-10-13T10:00:00Z',
    }]);

    const row = page.querySelector('tbody tr');
    expect(row?.textContent).toContain('P-1');
    expect(row?.textContent).toContain('Chaque jour du 01/03/2027 au 05/03/2027');
    expect(row?.textContent).toContain('quotidienne');
  });
});
