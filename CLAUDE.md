# ORPEX : consignes pour Claude Code

ORPEX = **O**rdonnanceur de **R**elances **P**lanifiées et **EX**pédiées. Lis aussi `README.md` (démarrage, état,
écarts) et `docs/specification.md` (spécification fonctionnelle, source de vérité des règles métier).

## Produit

Un utilisateur importe un fichier Excel : chaque ligne = un destinataire, un message et un calendrier d'envoi.
L'application calcule les jours d'envoi, demande si besoin une validation quotidienne, puis envoie automatiquement
par le relais SMTP de la banque. Cas d'usage : relances et rappels individualisés (facturation, suivi client), d'abord
vers d'autres départements de la banque (pas de données clients dans le pilote).
L'humain rédige les messages ; la machine ne fait que planifier et expédier. L'intérêt du produit est l'automatisme.

## Pile technique

- `backend/` : Java 21, Spring Boot 4.1.1 (Jackson 3, starters modulaires : `spring-boot-starter-webmvc`,
  `-flyway`, `-data-jpa`, etc.), Maven, PostgreSQL, Flyway.
- `frontend/` : Angular 22.2, composants autonomes, signaux, SCSS, tests Vitest. Aucune ressource externe
  (pas de CDN ni de police distante) : l'outil tourne dans un réseau bancaire.
- `docker-compose.yml` : PostgreSQL 17 et Mailpit (faux serveur de mail) pour le développement.
- Déploiement visé : serveur interne de la banque (JAR ou image Docker), jamais de service cloud tiers obligatoire.

## Commandes

```powershell
mvn -f backend clean verify              # compile + 25 tests du cœur métier
cd frontend; npm install; npx ng test --watch=false; npm start
.\scripts\dev.ps1                        # base + Mailpit (Docker) puis API, profil dev
.\scripts\build.ps1                      # front copié dans le JAR, puis mvn verify
```

Node requis : 22.22.3+ ou 24.15+ (Angular CLI 22).

## Architecture du code

- `backend/.../core/` : **Java pur, sans Spring**, testable sans base ni serveur.
  `calendar` (modes d'envoi, jours ouvrés, calcul des jours, activation), `importing` (validation des lignes,
  empreinte de configuration), `mail` (interface `MailGateway`, pas encore d'implémentation).
- `backend/.../config/` : sécurité (session par cookie, CSRF compatible Angular), propriétés `orpex.*`.
- `backend/src/main/resources/db/migration/` : Flyway. **Ne jamais modifier `V1__init.sql`** : ajouter `V2__...`.
- Les noms techniques et le code sont en anglais ; les colonnes Excel (`Columns`), les textes de l'interface et les
  messages destinés à l'utilisateur sont en français.

## Règles métier à ne pas casser (détail : `docs/specification.md`)

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

- Vérifié : front (build + 2 tests), cœur métier (25 tests, exécutés avec un JUnit de remplacement), schéma SQL sur
  PostgreSQL 16 (contraintes et triggers).
- **Non vérifié : tout ce qui dépend de Spring** (`pom.xml`, `config/`, `api/`). Premier travail : lancer
  `mvn -f backend clean verify` et corriger ce qui ne compile pas.

## Écarts connus avec la spécification

- Mots de passe en bcrypt (encodeur par défaut) en attendant Argon2id (BouncyCastle).
- `POST /login` exclu du CSRF (TODO dans `SecurityConfig`).
- Administrateur de démarrage lu dans la configuration, en attendant la lecture de la table `users`.
- État `VALIDATED` ajouté (absent du schéma de la spécification).
- Fuseau horaire par défaut `Europe/Paris`, à confirmer. Un envoi ponctuel un jour non ouvré est reporté au jour ouvré
  précédent, à confirmer.

## Prochaines étapes

1. `mvn verify` et corrections.
2. Comptes en base (lecture de `users`) et création du premier administrateur.
3. Import Excel : lecteur Apache POI (version figée), puis `RowValidator`, aperçu, création et mise à jour par `id`.
4. Génération des envois à l'activation (`ScheduleCalculator` + `ActivationPlanner`).
5. Worker d'envoi (`SELECT ... FOR UPDATE SKIP LOCKED`) et implémentation SMTP de `MailGateway`.
6. Validation quotidienne, journal d'audit, notifications.
7. Écrans Angular : relances, import avec aperçu, validation du jour, journal.
8. Livraison : image Docker et JAR lançable comme service.

## Façon de travailler

- Avancer par petites étapes vérifiables : compiler et lancer les tests avant de passer à la suite.
- Toute dépendance ajoutée a une version figée et récente (pas de `latest`) ; vérifier les versions avant de les écrire.
- Ne pas inventer de règle métier : en cas de doute, poser la question ou consulter `docs/specification.md`.
