import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { AuditApi, AuditView, Period } from '../audit/audit-api';
import { Auth } from '../auth/auth';
import { apiErrorMessage } from '../shared/api-error';
import { InstallationTimePipe } from '../shared/installation-time';

/** Journal d'audit (spécification §11) : ses relances et ses actions ; tout le journal pour l'administrateur. */
@Component({
  selector: 'app-journal',
  imports: [InstallationTimePipe],
  styleUrls: ['../shared/page.scss', './journal.scss'],
  template: `
    <h1>Journal</h1>
    <p class="aide">
      Toutes les actions sur vos relances (imports, modifications, validations, envois…) et vos connexions. Le journal
      ne peut être ni modifié ni effacé.
    </p>

    <form class="bloc creation" (submit)="$event.preventDefault(); charger()">
      <label>Du <input type="date" [value]="periode().from" (input)="date('from', $event)" /></label>
      <label>Au <input type="date" [value]="periode().to" (input)="date('to', $event)" /></label>
      @if (auth.isAdmin()) {
        <label class="case">
          <input type="checkbox" [checked]="tout()" (change)="basculerTout($event)" />
          Tout le journal (administrateur)
        </label>
      }
      <button type="submit" [disabled]="enCours()">Afficher</button>
      <a class="bouton" [href]="exportUrl()" download>Exporter en CSV</a>
    </form>

    @if (erreur()) {
      <p class="erreur" role="alert">{{ erreur() }}</p>
    }

    @if (lignes(); as l) {
      @if (l.length === 0) {
        <p class="info">Aucun événement sur cette période.</p>
      } @else {
        @if (l.length >= limite) {
          <p class="aide">Seuls les {{ limite }} événements les plus récents sont affichés : l'export contient tout.</p>
        }
        <table>
          <thead>
            <tr>
              <th scope="col">Date</th>
              <th scope="col">Auteur</th>
              <th scope="col">Événement</th>
              <th scope="col">Objet</th>
              <th scope="col">Détail</th>
            </tr>
          </thead>
          <tbody>
            @for (e of l; track e.id) {
              <tr>
                <td class="date">{{ e.occurredAt | heure: 'dd/MM/yyyy HH:mm:ss' }}</td>
                <td>{{ e.actor ?? 'système' }}</td>
                <td>{{ e.eventLabel }}</td>
                <td>{{ e.objectId ?? '' }}</td>
                <td class="detail">
                  @if (e.before) {
                    <span class="avant">{{ e.before }}</span> →
                  }
                  {{ e.after ?? '' }}
                </td>
              </tr>
            }
          </tbody>
        </table>
      }
    }
  `,
})
export class Journal implements OnInit {
  private readonly api = inject(AuditApi);
  protected readonly auth = inject(Auth);

  /** Lignes au plus à l'écran (comme le serveur). */
  protected readonly limite = 500;
  protected readonly periode = signal<Period>({ from: '', to: '' });
  protected readonly tout = signal(false);
  protected readonly lignes = signal<AuditView[] | null>(null);
  protected readonly erreur = signal<string | null>(null);
  protected readonly enCours = signal(false);
  protected readonly exportUrl = computed(() => this.api.exportUrl(this.tout(), this.periode()));

  async ngOnInit(): Promise<void> {
    await this.charger();
  }

  protected date(champ: keyof Period, event: Event): void {
    const valeur = (event.target as HTMLInputElement).value;
    this.periode.update((p) => ({ ...p, [champ]: valeur }));
  }

  protected async basculerTout(event: Event): Promise<void> {
    this.tout.set((event.target as HTMLInputElement).checked);
    await this.charger();
  }

  protected async charger(): Promise<void> {
    this.enCours.set(true);
    this.erreur.set(null);
    try {
      this.lignes.set(await this.api.list(this.tout(), this.periode()));
    } catch (error) {
      this.erreur.set(apiErrorMessage(error));
    } finally {
      this.enCours.set(false);
    }
  }
}
