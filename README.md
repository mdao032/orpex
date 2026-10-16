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
- Les mails envoyés en développement n'arrivent nulle part : ils s'affichent dans Mailpit, http://localhost:8026
  (SMTP sur le port 1026 ; ports décalés pour éviter un conflit avec un autre projet).
- Vérifier que l'API répond : http://localhost:8080/actuator/health
- Configuration : `application.properties` (commune, surchargeable par variables `ORPEX_*`) et
  `application-dev.properties` (surcharges du profil `dev` : base Docker, Mailpit, compte de développement).

## Construire

```powershell
.\scripts\build.ps1   # front -> copié dans le JAR -> mvn verify
docker build -t orpex:0.1.0 .
```

Résultat : `backend\target\orpex-backend-0.1.0-SNAPSHOT.jar`, qui sert l'API et l'interface. `build.ps1` réinstalle les
modules du front (`npm ci`) : arrêter `npm start` avant, sinon des fichiers verrouillés font échouer l'installation.

## Installer en production

Voir [`deploy/README.md`](deploy/README.md) : Docker, service Linux (systemd) ou service Windows (WinSW), variables
d'environnement, clé de chiffrement, sauvegarde.

## État d'avancement

Phases 0 à 8 terminées (import, envois, validation quotidienne, ré-import, journal d'audit, notifications) ;
phase 9 (livraison) en cours. Détail : la feuille de route.

## Écarts avec la spécification (à connaître)

- **État `VALIDATED`** : ajouté dans la base (envoi validé, en attente de son heure), absent du schéma de la spécification.
- **Fuseau horaire** : `Africa/Bamako` par défaut (pilote au Mali), réglable par `ORPEX_TIMEZONE`.
- **Jour non ouvré** : un envoi ponctuel tombant un jour non ouvré est reporté au dernier jour ouvré précédent,
  comme le dernier envoi d'une série « avant échéance ». À confirmer.
- **Résumé hebdomadaire** : en plus du résumé quotidien, chaque utilisateur peut choisir un résumé le vendredi soir ou le
  lundi matin.

## Prochaines étapes

Voir `orpex-docs/roadmaps/ROADMAP.md` : phases, critères de fin d'étape et journal d'avancement.
