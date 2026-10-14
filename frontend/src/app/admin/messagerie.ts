import { HttpClient } from '@angular/common/http';
import { Component, OnInit, inject, signal } from '@angular/core';
import { NonNullableFormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { firstValueFrom } from 'rxjs';
import { apiErrorMessage } from '../shared/api-error';

type Encryption = 'NONE' | 'STARTTLS' | 'TLS';

interface SmtpView {
  configured: boolean;
  host: string;
  port: number;
  encryption: Encryption;
  username: string | null;
  passwordSet: boolean;
  fromAddress: string;
  fromName: string | null;
  caCertificate: string | null;
  encryptionKeyAvailable: boolean;
}

@Component({
  selector: 'app-messagerie',
  imports: [ReactiveFormsModule],
  styleUrls: ['../shared/page.scss', './messagerie.scss'],
  template: `
    <h1>Messagerie</h1>

    @if (vue(); as v) {
      @if (!v.configured) {
        <p class="info" role="status">
          Aucun relais enregistré ici : l'application utilise la configuration du serveur ({{ v.host }}:{{ v.port }}).
          Enregistrez le relais de la banque pour qu'il soit utilisé.
        </p>
      }
      @if (!v.encryptionKeyAvailable) {
        <p class="erreur" role="alert">
          Aucune clé de chiffrement n'est configurée sur le serveur (ORPEX_SECRET_KEY) : un mot de passe ne peut pas
          être enregistré. Un relais sans authentification reste possible.
        </p>
      }

      <form class="bloc formulaire" [formGroup]="formulaire" (ngSubmit)="enregistrer()" novalidate>
        <h2>Relais SMTP</h2>
        <div class="ligne">
          <label>Serveur <input type="text" formControlName="host" required /></label>
          <label class="court">Port <input type="number" formControlName="port" min="1" max="65535" required /></label>
          <label>Chiffrement
            <select formControlName="encryption">
              <option value="STARTTLS">STARTTLS (port 587 en général)</option>
              <option value="TLS">TLS (port 465 en général)</option>
              <option value="NONE">Aucun (réseau interne seulement)</option>
            </select>
          </label>
        </div>

        <h2>Authentification</h2>
        <p class="aide">Laissez l'identifiant vide si le relais fait confiance à l'adresse IP du serveur.</p>
        <div class="ligne">
          <label>Identifiant <input type="text" formControlName="username" autocomplete="off" /></label>
          <label>Mot de passe
            <input type="password" formControlName="password" autocomplete="new-password"
                   [placeholder]="v.passwordSet ? 'Enregistré — laisser vide pour le conserver' : ''" />
          </label>
        </div>
        @if (v.passwordSet) {
          <label class="case"><input type="checkbox" formControlName="removePassword" /> Supprimer le mot de passe enregistré</label>
        }

        <h2>Expéditeur</h2>
        <div class="ligne">
          <label>Adresse <input type="email" formControlName="fromAddress" required /></label>
          <label>Nom affiché <input type="text" formControlName="fromName" /></label>
        </div>
        <p class="aide">Idéalement une boîte de service, par exemple relances&#64;banque.ml.</p>

        <h2>Certificat de l'autorité interne</h2>
        <p class="aide">
          Facultatif : si le relais présente un certificat émis par l'autorité interne de la banque, collez-la ici au
          format PEM (-----BEGIN CERTIFICATE----- …).
        </p>
        <textarea formControlName="caCertificate" rows="5" spellcheck="false"></textarea>

        <div class="confirmation">
          <button type="submit" [disabled]="enCours()">Enregistrer</button>
        </div>
      </form>
    }

    @if (message(); as m) {
      <p [class]="m.erreur ? 'erreur' : 'info'" [attr.role]="m.erreur ? 'alert' : 'status'">{{ m.texte }}</p>
    }
  `,
})
export class Messagerie implements OnInit {
  private readonly http = inject(HttpClient);

  protected readonly vue = signal<SmtpView | null>(null);
  protected readonly enCours = signal(false);
  protected readonly message = signal<{ texte: string; erreur: boolean } | null>(null);

  protected readonly formulaire = inject(NonNullableFormBuilder).group({
    host: ['', Validators.required],
    port: [587, Validators.required],
    encryption: ['STARTTLS' as Encryption],
    username: [''],
    password: [''],
    removePassword: [false],
    fromAddress: ['', [Validators.required, Validators.email]],
    fromName: [''],
    caCertificate: [''],
  });

  async ngOnInit(): Promise<void> {
    try {
      this.afficher(await firstValueFrom(this.http.get<SmtpView>('/api/admin/smtp')));
    } catch (error) {
      this.message.set({ texte: apiErrorMessage(error), erreur: true });
    }
  }

  protected async enregistrer(): Promise<void> {
    if (this.formulaire.invalid) {
      this.message.set({ texte: 'Renseignez le serveur, le port et une adresse expéditrice valide.', erreur: true });
      return;
    }
    this.enCours.set(true);
    try {
      const vue = await firstValueFrom(this.http.put<SmtpView>('/api/admin/smtp', this.formulaire.getRawValue()));
      this.afficher(vue);
      this.message.set({ texte: 'Configuration enregistrée : elle est utilisée dès maintenant.', erreur: false });
    } catch (error) {
      this.message.set({ texte: apiErrorMessage(error), erreur: true });
    } finally {
      this.enCours.set(false);
    }
  }

  private afficher(vue: SmtpView): void {
    this.vue.set(vue);
    this.formulaire.reset({
      host: vue.configured ? vue.host : '',
      port: vue.port,
      encryption: vue.configured ? vue.encryption : 'STARTTLS',
      username: vue.username ?? '',
      password: '',
      removePassword: false,
      fromAddress: vue.fromAddress,
      fromName: vue.fromName ?? '',
      caCertificate: vue.caCertificate ?? '',
    });
  }
}
