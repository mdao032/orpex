import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';

export type Role = 'USER' | 'ADMIN';

export interface Account {
  id: number;
  email: string;
  role: Role;
  enabled: boolean;
  locked: boolean;
  mustChangePassword: boolean;
  lastLoginAt: string | null;
  createdAt: string;
}

/** API d'administration des comptes (/api/admin/accounts). */
@Injectable({ providedIn: 'root' })
export class AccountsApi {
  private readonly http = inject(HttpClient);
  private readonly base = '/api/admin/accounts';

  list(): Promise<Account[]> {
    return firstValueFrom(this.http.get<Account[]>(this.base));
  }

  create(email: string, role: Role, temporaryPassword: string): Promise<Account> {
    return firstValueFrom(this.http.post<Account>(this.base, { email, role, temporaryPassword }));
  }

  disable(id: number): Promise<Account> {
    return firstValueFrom(this.http.post<Account>(`${this.base}/${id}/disable`, null));
  }

  enable(id: number): Promise<Account> {
    return firstValueFrom(this.http.post<Account>(`${this.base}/${id}/enable`, null));
  }

  changeRole(id: number, role: Role): Promise<Account> {
    return firstValueFrom(this.http.put<Account>(`${this.base}/${id}/role`, { role }));
  }

  resetPassword(id: number, temporaryPassword: string): Promise<Account> {
    return firstValueFrom(this.http.post<Account>(`${this.base}/${id}/password`, { temporaryPassword }));
  }
}
