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
      businessDaysOnly: true, dailyValidation: true, status: 'ACTIVE', paused: false, activatedAt: null,
      updatedAt: '2026-10-13T10:00:00Z', forecast: { planned: 5, first: '2027-03-01', last: '2027-03-05', missedToday: 0 },
    }]);

    const row = page.querySelector('tbody tr');
    expect(row?.textContent).toContain('P-1');
    expect(row?.textContent).toContain('Chaque jour du 01/03/2027 au 05/03/2027');
    expect(row?.textContent).toContain('quotidienne');
    expect(row?.textContent).toContain("À l'activation : 5 envoi(s) prévu(s) du 01/03/2027 au 05/03/2027.");
  });

  it('activates a rule and reports its sends', async () => {
    const fixture = TestBed.createComponent(Lignes);
    fixture.detectChanges();
    const http = TestBed.inject(HttpTestingController);
    const rule = {
      id: 7, businessId: 'P-7', recipientEmail: 'a@exemple.ml', firstName: null, lastName: null, caseReference: null,
      subject: 'Relance', sendMode: 'PERIODE', sendDate: null, dueDate: null, daysBefore: null,
      startDate: '2027-06-07', endDate: '2027-06-11', sendTime: '09:00:00', businessDaysOnly: true,
      dailyValidation: false, status: 'ACTIVE', paused: false, activatedAt: null, updatedAt: '2026-10-13T10:00:00Z',
      forecast: { planned: 5, first: '2027-06-07', last: '2027-06-11', missedToday: 0 },
    };
    http.expectOne('/api/rules').flush([rule]);
    await nextTick();
    await fixture.whenStable();
    const page = fixture.nativeElement as HTMLElement;
    expect(page.querySelector('tbody tr')?.textContent).toContain('Non activée');

    (page.querySelector('td.actions button') as HTMLButtonElement).click();
    http.expectOne('/api/rules/7/activate').flush({
      ruleId: 7, businessId: 'P-7', planned: 5, missed: 0, first: '2027-06-07', last: '2027-06-11',
    });
    await nextTick();
    http.expectOne('/api/rules').flush([{ ...rule, activatedAt: '2026-10-13T11:00:00Z', forecast: null }]);
    await nextTick();
    await fixture.whenStable();

    expect(page.querySelector('[role="status"]')?.textContent)
      .toContain('P-7 activée : 5 envoi(s) du 07/06/2027 au 11/06/2027.');
    expect(page.querySelector('tbody tr')?.textContent).toContain('Active');
    expect(page.querySelector('td.actions button')).toBeNull();
  });
});
