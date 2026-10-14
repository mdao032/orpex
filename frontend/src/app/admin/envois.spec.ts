import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { Envois } from './envois';

const nextTick = () => new Promise((resolve) => setTimeout(resolve));

describe('Envois', () => {
  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [Envois],
      providers: [provideHttpClient(), provideHttpClientTesting()],
    }).compileComponents();
  });

  it('suspends all sending after confirmation', async () => {
    const http = TestBed.inject(HttpTestingController);
    const fixture = TestBed.createComponent(Envois);
    fixture.detectChanges();
    http.expectOne('/api/sending-status').flush({ paused: false, changedAt: null });
    await nextTick();
    await fixture.whenStable();
    const page = fixture.nativeElement as HTMLElement;
    const confirmSpy = vi.spyOn(window, 'confirm').mockReturnValue(true);

    (page.querySelector('button') as HTMLButtonElement).click();
    http.expectOne('/api/admin/sending/pause').flush({ paused: true, missed: 0 });
    await nextTick();
    http.expectOne('/api/sending-status').flush({ paused: true, changedAt: '2026-10-14T08:00:00Z' });
    await nextTick();
    await fixture.whenStable();

    expect(page.textContent).toContain('Tous les envois sont suspendus');
    expect(page.querySelector('button')?.textContent).toContain('Reprendre les envois');
    confirmSpy.mockRestore();
  });
});
