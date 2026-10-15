import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { Pending } from '../validation/validation-api';
import { ValidationDuJour } from './validation-du-jour';

const nextTick = () => new Promise((resolve) => setTimeout(resolve));

const pending = (sendId: number, open = true): Pending => ({
  sendId, ruleId: sendId * 10, businessId: `D-${sendId}`, caseReference: `FAC-${sendId}`,
  recipientEmail: `d${sendId}@exemple.ml`, subject: 'Relance facture', scheduledAt: '2026-10-15T09:00:00Z',
  cutoffAt: '2026-10-15T08:30:00Z', open, alreadySent: 2,
});

describe('ValidationDuJour', () => {
  let http: HttpTestingController;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [ValidationDuJour],
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()],
    }).compileComponents();
    http = TestBed.inject(HttpTestingController);
  });

  async function render(list: Pending[]) {
    const fixture = TestBed.createComponent(ValidationDuJour);
    fixture.detectChanges();
    http.expectOne('/api/validation/today').flush(list);
    await nextTick();
    await fixture.whenStable();
    return { page: fixture.nativeElement as HTMLElement, fixture };
  }

  it('says when nothing awaits validation', async () => {
    const { page } = await render([]);
    expect(page.textContent).toContain("Aucune relance n'attend votre validation aujourd'hui.");
  });

  it('validates the checked sends and unchecks the others after confirmation', async () => {
    const { page, fixture } = await render([pending(1), pending(2), pending(3, false)]);
    const boxes = page.querySelectorAll<HTMLInputElement>('tbody input[type="checkbox"]');
    expect(boxes[0].checked).toBe(true);
    expect(boxes[2].disabled).toBe(true);
    expect(page.querySelectorAll('tbody tr')[2].textContent).toContain('Passée : ne partira pas');

    boxes[1].dispatchEvent(new Event('change'));
    await fixture.whenStable();
    const confirmSpy = vi.spyOn(window, 'confirm').mockReturnValue(true);
    (page.querySelector('.confirmation button') as HTMLButtonElement).click();

    expect(confirmSpy).toHaveBeenCalled();
    const decision = http.expectOne((req) => req.method === 'POST');
    expect(decision.request.body).toEqual({ validate: [1], reject: [2] });
    decision.flush({ validated: 1, rejected: 1, refused: [] });
    await nextTick();
    http.expectOne('/api/validation/today').flush([pending(3, false)]);
    await nextTick();
    await fixture.whenStable();

    expect(page.querySelector('[role="status"]')?.textContent)
      .toContain('1 relance(s) validée(s), 1 décochée(s) (ne partiront pas).');
    confirmSpy.mockRestore();
  });

  it('validates every open send at once', async () => {
    const { page } = await render([pending(1), pending(2)]);

    (page.querySelectorAll('.confirmation button')[1] as HTMLButtonElement).click();

    expect(http.expectOne((req) => req.method === 'POST').request.body).toEqual({ validate: [1, 2], reject: [] });
  });
});
