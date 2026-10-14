import { Component, effect, inject } from '@angular/core';
import { Router, RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { Auth } from './auth/auth';
import { SendingStatus } from './sending/sending-status';

@Component({
  imports: [RouterOutlet, RouterLink, RouterLinkActive],
  selector: 'app-root',
  styleUrl: './app.scss',
  templateUrl: './app.html',
})
export class App {
  protected readonly auth = inject(Auth);
  protected readonly envois = inject(SendingStatus);
  private readonly router = inject(Router);

  constructor() {
    // À chaque connexion : savoir si les envois sont suspendus, pour le bandeau.
    effect(() => {
      if (this.auth.user()) {
        void this.envois.refresh();
      }
    });
  }

  protected readonly title = 'ORPEX';
  protected readonly menu = [
    { chemin: '/lignes', libelle: 'Relances' },
    { chemin: '/import', libelle: 'Importer un fichier' },
    { chemin: '/validation', libelle: 'Validation du jour' },
    { chemin: '/journal', libelle: 'Journal' },
  ];

  protected async seDeconnecter(): Promise<void> {
    await this.auth.logout();
    await this.router.navigate(['/connexion']);
  }
}
