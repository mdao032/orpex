import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { Auth } from '../auth/auth';
import { MonCompte } from './mon-compte';

const nextTick = () => new Promise((resolve) => setTimeout(resolve));

describe('MonCompte', () => {
  const refresh = vi.fn().mockResolvedValue(null);

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [MonCompte],
      providers: [
        provideRouter([]),
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: Auth, useValue: { user: () => ({ summaryFrequency: 'DAILY' }), refresh } },
      ],
    }).compileComponents();
  });

  it('shows the current frequency and saves a weekly one', async () => {
    const http = TestBed.inject(HttpTestingController);
    const fixture = TestBed.createComponent(MonCompte);
    fixture.detectChanges();
    await fixture.whenStable();
    const page = fixture.nativeElement as HTMLElement;
    const radios = Array.from(page.querySelectorAll('input[type="radio"]')) as HTMLInputElement[];
    expect(radios.map((r) => r.checked)).toEqual([true, false, false]);

    radios[2].click();
    const save = http.expectOne('/api/me/summary-frequency');
    expect(save.request.body).toEqual({ frequency: 'WEEKLY_MONDAY' });
    save.flush({ frequency: 'WEEKLY_MONDAY' });
    await nextTick();
    await fixture.whenStable();

    expect(refresh).toHaveBeenCalled();
    expect(page.textContent).toContain('Résumé : chaque semaine, le lundi en début de journée.');
    expect(page.querySelector('a[href="/mot-de-passe"]')).not.toBeNull();
  });
});
