# ORPEX

**O**rdonnanceur de **R**elances **P**lanifiées et **EX**pédiées.

Un fichier Excel décrit des relances (une ligne = un destinataire, un message, un calendrier). L'application les
planifie, demande si besoin une validation quotidienne, puis les envoie automatiquement par le relais SMTP de la banque.
La spécification fonctionnelle complète est dans le document partagé avec l'utilisateur final ; une copie
(export Markdown ou Word) peut être déposée dans `docs/`.

## Structure

| Dossier | Contenu |
| --- | --- |
| `backend/` | API et worker : Java 21, Spring Boot 4.1, PostgreSQL, Flyway (Maven) |
| `frontend/` | Interface : Angular 22 (composants autonomes, signaux, tests Vitest) |
| `docker-compose.yml` | PostgreSQL et faux serveur de mail (Mailpit) pour le développement |
| `scripts/` | `dev.ps1` (démarrer en local), `build.ps1` (construire le JAR complet) |

Le cœur métier est du Java pur, sans Spring (`backend/.../core/`) : calendrier et jours ouvrés, validation des lignes
du fichier, empreinte de configuration, interface d'envoi. Il se teste sans base ni serveur.

## Prérequis

- JDK 21 et Maven 3.9 ou plus récent
- Node 22.22.3 ou plus récent, ou 24.15 ou plus récent (exigence d'Angular CLI 22)
- Docker Desktop (base et faux serveur de mail)

## Démarrer en développement

```powershell
# 1. Base de données + faux serveur de mail, puis l'API (profil dev)
.\scripts\dev.ps1

# 2. Dans un autre terminal : l'interface (http://localhost:4200, appels /api renvoyés vers le port 8080)
cd frontend
npm install
npm start
```

- Compte de développement : `admin@orpex.local` (mot de passe `dev-only-change-me`, profil `dev` uniquement).
- Les mails envoyés en développement n'arrivent nulle part : ils s'affichent dans Mailpit, http://localhost:8025.
- Vérifier que l'API répond : http://localhost:8080/actuator/health

## Construire

```powershell
.\scripts\build.ps1   # front -> copié dans le JAR -> mvn verify
```

Résultat : `backend\target\orpex-backend-0.1.0-SNAPSHOT.jar`, qui sert l'API et l'interface.

## État d'avancement

| Élément | Statut |
| --- | --- |
| Front Angular : menu, 4 pages vides, proxy vers l'API | Compile et ses 2 tests passent |
| Cœur métier Java (calendrier, activation, validation des lignes, empreinte) | 25 tests passent (compilés avec javac 21 et un JUnit minimal de remplacement) |
| Schéma SQL `V1__init.sql` | Appliqué sur PostgreSQL 16 ; unicités, contrôles et triggers de conservation vérifiés |
| Partie Spring (`pom.xml`, configuration, sécurité, `/api/me`) | **Non compilée** : Maven Central n'était pas joignable pendant la génération |

**Premier réflexe : lancer `mvn -f backend verify`** et me renvoyer les erreurs éventuelles. Les classes de
Spring Security utilisées ont été vérifiées dans le code source de la branche principale, mais pas compilées.

## Écarts avec la spécification (à connaître)

- **Mots de passe** : encodeur par défaut de Spring Security (bcrypt) en attendant Argon2id (demande BouncyCastle).
- **CSRF** : le `POST /login` est exclu de la protection pour l'instant (TODO dans `SecurityConfig`).
- **Comptes** : un administrateur de démarrage lu dans la configuration remplace temporairement la table `users`.
- **État `VALIDATED`** : ajouté dans la base (envoi validé, en attente de son heure), absent du schéma de la spécification.
- **Fuseau horaire** : `Europe/Paris` par défaut, à confirmer avec la banque (spécification, section 15).
- **Jour non ouvré** : un envoi ponctuel tombant un jour non ouvré est reporté au dernier jour ouvré précédent,
  comme le dernier envoi d'une série « avant échéance ». À confirmer.

## Prochaines étapes

1. `mvn verify` et correction des erreurs de compilation éventuelles.
2. Comptes en base (lecture de `users`) et création du premier administrateur.
3. Import Excel : lecteur (Apache POI, version figée) puis `RowValidator`, aperçu, création et mise à jour par `id`.
4. Génération des envois (`ScheduleCalculator` + `ActivationPlanner`) à l'activation d'une ligne.
5. Worker d'envoi (`SELECT ... FOR UPDATE SKIP LOCKED`) et implémentation SMTP de `MailGateway`.
6. Validation quotidienne, journal d'audit, notifications.
7. Écrans Angular : liste des relances, import avec aperçu, validation du jour, journal.
8. Livraison : image Docker et JAR lançable comme service.
