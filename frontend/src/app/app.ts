import { Component } from '@angular/core';
import { RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';

@Component({
  imports: [RouterOutlet, RouterLink, RouterLinkActive],
  selector: 'app-root',
  styleUrl: './app.scss',
  templateUrl: './app.html',
})
export class App {
  protected readonly title = 'ORPEX';
  protected readonly menu = [
    { chemin: '/lignes', libelle: 'Relances' },
    { chemin: '/import', libelle: 'Importer un fichier' },
    { chemin: '/validation', libelle: 'Validation du jour' },
    { chemin: '/journal', libelle: 'Journal' },
  ];
}
