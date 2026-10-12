import { Component, inject } from '@angular/core';
import { Router, RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { Auth } from './auth/auth';

@Component({
  imports: [RouterOutlet, RouterLink, RouterLinkActive],
  selector: 'app-root',
  styleUrl: './app.scss',
  templateUrl: './app.html',
})
export class App {
  protected readonly auth = inject(Auth);
  private readonly router = inject(Router);

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
