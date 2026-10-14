import { HttpClient } from '@angular/common/http';
import { Injectable, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';

export interface PauseState {
  paused: boolean;
  changedAt: string | null;
}

/** Pause générale des envois : lue par tous (bandeau), changée par un administrateur. */
@Injectable({ providedIn: 'root' })
export class SendingStatus {
  private readonly http = inject(HttpClient);
  private readonly current = signal<PauseState>({ paused: false, changedAt: null });

  readonly state = this.current.asReadonly();

  async refresh(): Promise<void> {
    try {
      this.current.set(await firstValueFrom(this.http.get<PauseState>('/api/sending-status')));
    } catch {
      // état inconnu : on n'affiche pas de bandeau plutôt qu'une information fausse
    }
  }

  /** Renvoie le nombre d'envois devenus « manqués » (à la reprise). */
  async setPaused(paused: boolean): Promise<number> {
    const action = paused ? 'pause' : 'resume';
    const result = await firstValueFrom(
      this.http.post<{ paused: boolean; missed: number }>(`/api/admin/sending/${action}`, null),
    );
    await this.refresh();
    return result.missed;
  }
}
