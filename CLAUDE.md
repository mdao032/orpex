# ORPEX : consignes pour Claude Code

ORPEX = **O**rdonnanceur de **R**elances **P**lanifiées et **EX**pédiées. Lis aussi `README.md` (démarrage, écarts).

Documents hors dépôt (dossier `E:\Projets\orpex-docs`) :
- Spécification fonctionnelle, source de vérité des règles métier : `E:\Projets\orpex-docs\specs\Spécification fonctionnelle – Relances planifiées par import Excel.docx`.
- **Feuille de route à suivre** : `E:\Projets\orpex-docs\roadmaps\ROADMAP.md`. Avant de commencer, repérer la
  première étape non cochée ; une fois son critère « Fait quand » vérifié, la cocher et ajouter une ligne au journal.

## Produit

Un utilisateur importe un fichier Excel : chaque ligne = un destinataire, un message et un calendrier d'envoi.
L'application calcule les jours d'envoi, demande si besoin une validation quotidienne, puis envoie automatiquement
par le relais SMTP de la banque. Cas d'usage : relances et rappels individualisés (facturation, suivi client), d'abord
vers d'autres départements de la banque (pas de données clients dans le pilote). **Le pilote se déroule au Mali** :
fuseau `Africa/Bamako`, jours fériés maliens.
L'humain rédige les messages ; la machine ne fait que planifier et expédier. L'intérêt du produit est l'automatisme.

## Pile technique

- `backend/` : Java 21, Spring Boot 4.1.1 (Jackson 3, starters modulaires : `spring-boot-starter-webmvc`,
  `-flyway`, `-data-jpa`, etc.), Maven, PostgreSQL, Flyway.
- `frontend/` : Angular 22.2, composants autonomes, signaux, SCSS, tests Vitest. Aucune ressource externe
  (pas de CDN ni de police distante) : l'outil tourne dans un réseau bancaire.
- `docker-compose.yml` : PostgreSQL 17 (port hôte **5435**) et Mailpit (SMTP 1025, interface
  http://localhost:8025) pour le développement.
- Dépôt unique (back + front) : https://github.com/mdao032/orpex, branche `main`.
- Déploiement visé : serveur interne de la banque (JAR ou image Docker), jamais de service cloud tiers obligatoire.

## Commandes

```powershell
mvn -f backend clean verify              # tests unitaires + tests d'intégration *IT (Docker requis)
cd frontend; npm install; npx ng test --watch=false; npm start
.\scripts\dev.ps1                        # base + Mailpit (Docker) puis API, profil dev
.\scripts\build.ps1                      # front copié dans le JAR, puis mvn verify
```

Node requis : 22.22.3+ ou 24.15+ (Angular CLI 22).

## Architecture du code

- `backend/.../core/` : **Java pur, sans Spring**, testable sans base ni serveur.
  `calendar` (modes d'envoi, jours ouvrés, calcul des jours, activation), `importing` (lecture .xlsx `XlsxReader`
  avec Apache POI, validation des lignes, empreinte de configuration), `mail` (interface `MailGateway`, rendu des
  variables `MessageTemplate` : une variable sans valeur fait échouer l'envoi, jamais de champ vide).
- `backend/.../sending/` : `SendWorker` (toutes les 30 s, `orpex.worker.*`) : rien pendant une pause générale ;
  envoi dû depuis plus de 15 min → `MISSED` ; réservation `FOR UPDATE SKIP LOCKED` → `SENDING` ; envoi hors
  transaction → `SENT` ; échec temporaire → nouvelle tentative (`next_attempt_at`, +1/+5/+15 min, état d'attente
  conservé) ; échec définitif ou tentatives épuisées → `FAILED`. Seuls `PLANNED` et `VALIDATED` de relances non
  closes ni en pause partent.
  Envoi resté `SENDING` plus de 10 min (arrêt pendant l'envoi) → `TO_CHECK` (au démarrage et à chaque passage),
  **jamais renvoyé** : le propriétaire tranche (`/api/rules/{id}/sends/{sendId}/resolve`) → `SENT` ou `FAILED`.
  Désactivé pendant les tests (`orpex.worker.enabled=false` via failsafe) : les tests appellent `processDueSends()`.
- `backend/.../mail/` : `SmtpMailGateway` (Spring Mail, `spring.mail.*`, expéditeur `orpex.mail.*`) ; erreurs classées
  temporaires (4xx, serveur injoignable) ou définitives (5xx, adresse ou authentification refusée). Les valeurs des
  variables (colonnes libres comprises) sont relevées à l'import (`rules.variables`, JSON).
- `backend/.../account/` : comptes (table `users`, email normalisé en minuscules), connexion par
  `DatabaseUserDetailsService`, premier administrateur créé depuis `orpex.bootstrap-admin.*` si la table est vide.
  Mots de passe en Argon2id ; un ancien hachage bcrypt est converti à la connexion réussie suivante.
  Verrouillage temporaire après échecs (`orpex.login.*`, 5 échecs → 15 min) par `LoginAttemptListener` ; même 401
  pour mauvais mot de passe, compte inconnu ou verrouillé. Session : 30 min d'inactivité, `POST /logout`.
  Administration (`AccountService`, `/api/admin/accounts`) : créer, désactiver/réactiver, changer le rôle, réinitialiser
  le mot de passe ; un admin ne peut ni se désactiver ni se rétrograder. Mot de passe saisi par un tiers = provisoire
  (`must_change_password`) : `AccountStatusFilter` bloque l'API (403 `PASSWORD_CHANGE_REQUIRED`) sauf `/api/me` et
  `PUT /api/me/password`, et ferme la session d'un compte désactivé ou dont le rôle a changé. Mots de passe : 12 à 128
  caractères (`orpex.password.*`). Erreurs de l'API : RFC 9457, message pour l'utilisateur dans `detail`.
- `backend/.../holiday/` : jours fériés (table `calendar_days`) ; `HolidayService.businessCalendar()` construit le
  `BusinessCalendar` du cœur métier, à relire à chaque calcul d'envois. Administration : `/api/admin/holidays`.
  Jours fériés d'un pays (`core/calendar/NationalHolidays` : France, Mali) : seuls les jours calculables sont ajoutés ;
  les fêtes à date annoncée (fêtes musulmanes au Mali) sont listées pour une saisie à la main.
- `backend/.../rule/` : entités `Rule` (table `rules`), `Occurrence` (`occurrences`, un envoi = une ligne, un jour)
  et `ImportRecord` (`imports`). Activation **par bouton** (`ActivationService`, `POST /api/rules/{id}/activate`,
  `/activate-all`) : crée les envois (`PLANNED`, ou `AWAITING_VALIDATION` si validation quotidienne ; `MISSED` pour
  l'envoi du jour dont l'heure est passée), verrou de ligne contre la double activation.
  Clôture (`ClosureService`, `POST /api/rules/{id}/close`) : statut clos, envois non commencés (`NOT_STARTED`)
  passés en `CANCELLED`, jamais supprimés ; pas de réouverture (non prévue par la spécification).
  Pauses (`SendingControlService`) : par relance (propriétaire, `/api/rules/{id}/pause|resume`) et générale
  (admin, `/api/admin/sending/pause|resume`, table `sending_control`, bandeau via `/api/sending-status`). À la reprise,
  les envois dont l'heure est passée deviennent `MISSED`. **Le worker d'envoi devra ignorer les relances en pause et
  ne rien envoyer pendant une pause générale.**
  Jours fériés modifiés (Q10) : `RescheduleService` recalcule dans la même transaction les envois futurs non
  commencés des relances activées en jours ouvrés (annulé, ajouté, ou annulé repris) ; le passé n'est jamais touché. Les dépôts n'exposent que des
  méthodes filtrées par propriétaire (`...ByOwnerId...`) : **ne jamais ajouter de `findAll`/`findById` non filtré**.
- `backend/.../importing/` : `ImportPreviewService` (`POST /api/imports/preview`, multipart `file`, 5 Mo max) lit,
  valide, compare à la base (créer / modifier / inchangée / rejetée) et calcule les envois, **sans rien écrire** ;
  la confirmation (`POST /api/imports`, même fichier, même analyse) crée les nouvelles lignes en une transaction et
  enregistre l'import ; une ligne existante modifiée est comptée « non appliquée » (mise à jour : phase 7).
  Les lignes créées n'ont aucun envoi tant qu'elles ne sont pas activées.
  Modèle à télécharger : `GET /api/imports/template` (`core/importing/ImportTemplate`, exemples en `@example.org`).
- `frontend/src/app/auth/` : service `Auth` (utilisateur via `/api/me`, interrogé au démarrage, ce qui fournit aussi le
  cookie CSRF), `authGuard` sur toutes les pages sauf `/connexion` (et redirection vers `/mot-de-passe` si provisoire),
  `adminGuard` pour `/admin/**`, intercepteur (401 → connexion, 403 `PASSWORD_CHANGE_REQUIRED` → mot de passe).
  Messages d'erreur de l'API : `apiErrorMessage()` (`shared/api-error.ts`).
  Pages : `pages/import-fichier` (modèle, aperçu, confirmation, historique), `pages/lignes` (relances, liens vers
  le détail), `pages/relance` (`/lignes/:id`, message, calendrier, envois) ; libellés partagés (états, messages
  d'action) dans `shared/rule-labels.ts` ; styles
  communs dans `shared/page.scss`, calendrier en français par `shared/schedule-label.ts`.
  Pages admin dans `admin/` (comptes, jours fériés). Locale `fr` active (dates en français via `DatePipe`).
- Heure : bean `Clock` dans le fuseau `orpex.timezone` (défaut `Africa/Bamako`, invalide = démarrage refusé). Calculer
  « aujourd'hui » et les heures d'envoi avec ce `Clock`, jamais avec le fuseau du serveur.
- `backend/.../config/` : sécurité (session par cookie, CSRF compatible Angular), propriétés `orpex.*`.
- Configuration : `application.properties` (commune, valeurs surchargeables par variables `ORPEX_*`) et
  `application-dev.properties` (uniquement les surcharges du profil `dev`). Pas de YAML.
- `backend/src/main/resources/db/migration/` : Flyway. **Ne jamais modifier `V1__init.sql`** : ajouter `V2__...`.
- Les noms techniques et le code sont en anglais ; les colonnes Excel (`Columns`), les textes de l'interface et les
  messages destinés à l'utilisateur sont en français.

## Règles métier à ne pas casser (détail : la spécification)

- Trois modes : `ponctuel`, `avant_echeance` (de échéance moins N jours à l'échéance, bornes incluses), `periode`.
- Avec `jours_ouvres_uniquement`, les week-ends et jours fériés sont sautés ; une échéance non ouvrée reporte le
  dernier envoi au dernier jour ouvré précédent, sans doublon.
- À l'activation : pas de rattrapage du passé ; un envoi du jour dont l'heure est passée devient `MISSED`.
- `validation_quotidienne` : `oui` ou `non`, **jamais vide**. Avec `oui`, **sans validation rien ne part**, et un envoi
  non validé ne part jamais en retard de lui-même.
- Ré-import : mise à jour par `id` (unique par propriétaire), aperçu avant application, rien n'est supprimé, et en cas
  de divergence entre le fichier et l'interface l'utilisateur choisit lequel gagne : **jamais d'écrasement silencieux**.
- Un envoi est unique par (ligne, jour). Statut `SENDING` pendant l'envoi ; après un arrêt entre l'acceptation par le
  serveur et l'écriture du statut, l'envoi passe en `TO_CHECK` : **jamais de renvoi automatique**.
- États d'un envoi : `PLANNED`, `AWAITING_VALIDATION`, `VALIDATED`, `SENDING`, `SENT`, `FAILED`, `TO_CHECK`,
  `NOT_VALIDATED`, `CANCELLED`, `MISSED`.
- Journal d'audit en ajout seul (triggers en base) ; aucune suppression physique des envois.
- V1 : le validateur est le propriétaire de la ligne ; la colonne `validateur` est ignorée avec un avertissement.
- Les secrets (mot de passe SMTP) sont chiffrés au repos, la clé n'est jamais en base.

## État actuel

- Phases 0 à 4 terminées (fondations, comptes et sécurité, jours fériés, import Excel, activation et envois
  planifiés). Phase 5 : envoi réel des mails.
  Toujours relancer **toute** la suite (`mvn -f backend clean verify` et `npx ng test --watch=false`) avant un commit.
- `OrpexApplicationIT` démarre toute l'application sur un PostgreSQL 17 jetable (Testcontainers, `@ServiceConnection`)
  et vérifie les migrations Flyway. Tests d'intégration : suffixe `IT`, exécutés par failsafe pendant `mvn verify`.
- CSRF actif sur toutes les requêtes qui modifient, `POST /login` compris. Dans les tests MockMvc, utiliser
  `CsrfSupport.realCsrf(mvc)`, **jamais** `csrf()` de spring-security-test (il casse le cookie pour tout le contexte partagé).
- Les entités JPA sont validées contre le schéma au démarrage (`ddl-auto=validate`).

## Écarts connus avec la spécification

- État `VALIDATED` ajouté (absent du schéma de la spécification).
- Un envoi ponctuel un jour non ouvré est reporté au jour ouvré précédent, à confirmer.

## Prochaines étapes

Voir la feuille de route (`orpex-docs/roadmaps/ROADMAP.md`), seule liste à jour.

## Façon de travailler

- Avancer par petites étapes vérifiables : compiler et lancer les tests avant de passer à la suite.
- Toute dépendance ajoutée a une version figée et récente (pas de `latest`) ; vérifier les versions avant de les écrire.
- Ne pas inventer de règle métier : en cas de doute, poser la question ou consulter la spécification.
