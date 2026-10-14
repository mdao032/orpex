import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { App } from './app';
import { Auth } from './auth/auth';

describe('App', () => {
  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [App],
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()],
    }).compileComponents();
  });

  it('shows only the product name when nobody is logged in', async () => {
    const fixture = TestBed.createComponent(App);
    await fixture.whenStable();
    const compiled = fixture.nativeElement as HTMLElement;
    expect(compiled.querySelector('.marque')?.textContent).toContain('ORPEX');
    expect(compiled.querySelectorAll('nav a').length).toBe(0);
    expect(compiled.querySelector('.deconnexion')).toBeNull();
  });

  it('shows the main menu, the admin entry and the logged-in user', async () => {
    const refreshed = TestBed.inject(Auth).refresh();
    TestBed.inject(HttpTestingController)
      .expectOne('/api/me')
      .flush({ email: 'admin@orpex.local', roles: ['ROLE_ADMIN'], mustChangePassword: false });
    await refreshed;

    const fixture = TestBed.createComponent(App);
    await fixture.whenStable();
    const compiled = fixture.nativeElement as HTMLElement;
    expect(compiled.querySelectorAll('nav a').length).toBe(7); // 4 pages + Comptes, Jours fériés, Envois (admin)
    expect(compiled.querySelector('.utilisateur')?.textContent).toContain('admin@orpex.local');
    expect(compiled.querySelector('.deconnexion')).not.toBeNull();
  });

  it('warns every user when all sending is paused', async () => {
    const http = TestBed.inject(HttpTestingController);
    const refreshed = TestBed.inject(Auth).refresh();
    http.expectOne('/api/me').flush({ email: 'a@b.c', roles: ['ROLE_USER'], mustChangePassword: false });
    await refreshed;

    const fixture = TestBed.createComponent(App);
    fixture.detectChanges();
    http.expectOne('/api/sending-status').flush({ paused: true, changedAt: '2026-10-14T08:00:00Z' });
    await new Promise((resolve) => setTimeout(resolve));
    await fixture.whenStable();

    expect((fixture.nativeElement as HTMLElement).querySelector('.bandeau-pause')?.textContent)
      .toContain('Tous les envois sont suspendus');
  });
});
