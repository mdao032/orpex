import { Routes } from '@angular/router';
import { authGuard } from './auth/auth-guard';

export const routes: Routes = [
  {
    path: 'connexion',
    title: 'Connexion – ORPEX',
    loadComponent: () => import('./pages/connexion').then((m) => m.Connexion),
  },
  {
    path: '',
    canActivateChild: [authGuard],
    children: [
      { path: '', pathMatch: 'full', redirectTo: 'lignes' },
      {
        path: 'lignes',
        title: 'Relances – ORPEX',
        loadComponent: () => import('./pages/lignes').then((m) => m.Lignes),
      },
      {
        path: 'import',
        title: 'Importer un fichier – ORPEX',
        loadComponent: () => import('./pages/import-fichier').then((m) => m.ImportFichier),
      },
      {
        path: 'validation',
        title: 'Validation du jour – ORPEX',
        loadComponent: () => import('./pages/validation-du-jour').then((m) => m.ValidationDuJour),
      },
      {
        path: 'journal',
        title: 'Journal – ORPEX',
        loadComponent: () => import('./pages/journal').then((m) => m.Journal),
      },
    ],
  },
  { path: '**', redirectTo: 'lignes' },
];
