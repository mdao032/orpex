# ORPEX

**O**rdonnanceur de **R**elances **P**lanifiées et **EX**pédiées.

Un fichier Excel décrit des relances (une ligne = un destinataire, un message, un calendrier). L'application les
planifie, demande si besoin une validation quotidienne, puis les envoie automatiquement par le relais SMTP de la banque.
La spécification fonctionnelle et la feuille de route sont tenues hors du dépôt, dans le dossier `orpex-docs`
(`specs/` et `roadmaps/ROADMAP.md`).

## Structure

| Dossier | Contenu |
| --- | --- |
| `backend/` | API et worker : Java 21, Spring Boot 4.1, PostgreSQL, Flyway (Maven) |
| `frontend/` | Interface : Angular 22 (composants autonomes, signaux, tests Vitest) |
| `docker-compose.yml` | PostgreSQL 17 (port hôte 5435) et faux serveur de mail (Mailpit) pour le développement |
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

- Compte de développement : `admin@orpex.local` (mot de passe `dev-only-change-me`), créé au premier démarrage
  en profil `dev` si la table `users` est vide.
- Les mails envoyés en développement n'arrivent nulle part : ils s'affichent dans Mailpit, http://localhost:8025.
- Vérifier que l'API répond : http://localhost:8080/actuator/health
- Configuration : `application.properties` (commune, surchargeable par variables `ORPEX_*`) et
  `application-dev.properties` (surcharges du profil `dev` : base Docker, Mailpit, compte de développement).

## Construire

```powershell
.\scripts\build.ps1   # front -> copié dans le JAR -> mvn verify
```

Résultat : `backend\target\orpex-backend-0.1.0-SNAPSHOT.jar`, qui sert l'API et l'interface.

## État d'avancement

| Élément | Statut |
| --- | --- |
| Front Angular : menu, 4 pages vides, proxy vers l'API | Compile, 2 tests passent, s'affiche |
| Cœur métier Java (calendrier, activation, validation des lignes, empreinte) | 25 tests passent |
| Partie Spring (configuration, sécurité, `/api/me`) | Compile ; l'API démarre, connexion et `/api/me` vérifiées |
| Schéma SQL `V1__init.sql` | Appliqué par Flyway sur PostgreSQL 17 |
| Import Excel, envois, validation quotidienne, journal | Pas commencé (voir la feuille de route) |

## Écarts avec la spécification (à connaître)

- **État `VALIDATED`** : ajouté dans la base (envoi validé, en attente de son heure), absent du schéma de la spécification.
- **Fuseau horaire** : `Europe/Paris` par défaut, à confirmer avec la banque (spécification, section 15).
- **Jour non ouvré** : un envoi ponctuel tombant un jour non ouvré est reporté au dernier jour ouvré précédent,
  comme le dernier envoi d'une série « avant échéance ». À confirmer.

## Prochaines étapes

Voir `orpex-docs/roadmaps/ROADMAP.md` : phases, critères de fin d'étape et journal d'avancement.
