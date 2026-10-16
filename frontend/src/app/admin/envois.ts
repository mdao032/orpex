import { HttpClient } from '@angular/common/http';
import { Component, OnInit, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { SendingStatus } from '../sending/sending-status';
import { apiErrorMessage } from '../shared/api-error';
import { InstallationTimePipe } from '../shared/installation-time';

@Component({
  selector: 'app-envois',
  imports: [InstallationTimePipe],
  styleUrl: '../shared/page.scss',
  template: `
    <h1>Envois</h1>

    <section class="bloc" aria-labelledby="titre-pause">
      <h2 id="titre-pause">Pause générale</h2>
      @if (statut.state(); as s) {
        @if (s.paused) {
          <p class="erreur" role="status">
            Tous les envois sont suspendus
            @if (s.changedAt) { depuis le {{ s.changedAt | heure: 'dd/MM/yyyy à HH:mm' }} }.
          </p>
          <button type="submit" (click)="changer(false)" [disabled]="enCours()">Reprendre les envois</button>
        } @else {
          <p class="info" role="status">Les envois fonctionnent normalement.</p>
          <button type="submit" (click)="changer(true)" [disabled]="enCours()">Suspendre tous les envois</button>
        }
      }
      <p class="aide">
        La pause concerne toutes les relances de tous les utilisateurs (incident sur le relais de messagerie, par
        exemple). Rien n'est supprimé ; à la reprise, les envois dont l'heure est passée pendant la pause sont marqués
        « manqués » et ne partent pas en retard.
      </p>
    </section>

    <section class="bloc" aria-labelledby="titre-journee">
      <h2 id="titre-journee">Journée de travail</h2>
      <form class="creation" (submit)="$event.preventDefault(); enregistrerJournee()">
        <label>Début <input type="time" required [value]="journee().start" (input)="heure('start', $event)" /></label>
        <label>Fin <input type="time" required [value]="journee().end" (input)="heure('end', $event)" /></label>
        <button type="submit" [disabled]="enCours()">Enregistrer</button>
      </form>
      <p class="aide">
        Chaque utilisateur reçoit un résumé de ses relances (envoyées, en échec, non parties, à venir), à la fréquence
        qu'il choisit dans « Mon compte » : chaque jour ouvré à l'heure de fin, ou chaque semaine, le vendredi à l'heure de
        fin ou le lundi à l'heure de début. Les alertes d'échec partent dès qu'un envoi échoue, au propriétaire et aux
        administrateurs ; les administrateurs sont aussi prévenus quand le relais de messagerie ne répond plus.
      </p>
    </section>

    @if (message(); as m) {
      <p [class]="m.erreur ? 'erreur' : 'info'" [attr.role]="m.erreur ? 'alert' : 'status'">{{ m.texte }}</p>
    }
  `,
})
export class Envois implements OnInit {
  protected readonly statut = inject(SendingStatus);
  protected readonly enCours = signal(false);
  protected readonly message = signal<{ texte: string; erreur: boolean } | null>(null);
  /** Heures HH:mm de la journée de travail. */
  protected readonly journee = signal<Workday>({ start: '', end: '' });
  private readonly http = inject(HttpClient);

  async ngOnInit(): Promise<void> {
    await this.statut.refresh();
    try {
      this.journee.set(court(await firstValueFrom(this.http.get<Workday>('/api/admin/workday'))));
    } catch (error) {
      this.message.set({ texte: apiErrorMessage(error), erreur: true });
    }
  }

  protected heure(champ: keyof Workday, event: Event): void {
    const valeur = (event.target as HTMLInputElement).value;
    this.journee.update((j) => ({ ...j, [champ]: valeur }));
  }

  protected async enregistrerJournee(): Promise<void> {
    this.enCours.set(true);
    try {
      const j = court(await firstValueFrom(this.http.put<Workday>('/api/admin/workday', this.journee())));
      this.journee.set(j);
      this.message.set({ texte: `Journée de travail : ${j.start} – ${j.end}. Résumé quotidien à ${j.end}.`, erreur: false });
    } catch (error) {
      this.message.set({ texte: apiErrorMessage(error), erreur: true });
    } finally {
      this.enCours.set(false);
    }
  }

  protected async changer(pause: boolean): Promise<void> {
    const question = pause
      ? 'Suspendre tous les envois de tous les utilisateurs ?'
      : "Reprendre les envois ? Ceux dont l'heure est passée pendant la pause seront marqués manqués.";
    if (!confirm(question)) {
      return;
    }
    this.enCours.set(true);
    try {
      const manques = await this.statut.setPaused(pause);
      this.message.set({
        texte: pause ? 'Envois suspendus.' : `Envois repris : ${manques} envoi(s) manqué(s) pendant la pause.`,
        erreur: false,
      });
    } catch (error) {
      this.message.set({ texte: apiErrorMessage(error), erreur: true });
    } finally {
      this.enCours.set(false);
    }
  }
}

interface Workday {
  start: string;
  end: string;
}

/** Le serveur renvoie HH:mm:ss ; l'écran affiche HH:mm. */
function court(j: Workday): Workday {
  return { start: j.start.slice(0, 5), end: j.end.slice(0, 5) };
}
