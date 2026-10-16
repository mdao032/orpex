# Installer ORPEX en production

ORPEX est un seul programme Java (un JAR qui contient l'interface et l'API) et une base PostgreSQL. Il tourne sur un
serveur interne de la banque, au choix :

- **dans Docker** (image construite depuis ce dépôt) ;
- **comme service Linux** (systemd) ;
- **comme service Windows** (WinSW).

Rien n'est appelé sur Internet : ni CDN, ni police distante, ni service cloud.

## 1. Prérequis

| Élément | Version |
| --- | --- |
| Java (JRE ou JDK) | 21 (sauf avec Docker : inclus dans l'image) |
| PostgreSQL | 17 recommandé ; une base `orpex` et un compte propriétaire de cette base |
| Relais SMTP de la banque | hôte, port, chiffrement, authentification (voir l'écran « Messagerie ») |
| Accès réseau | serveur ORPEX → PostgreSQL, serveur ORPEX → relais SMTP ; postes des utilisateurs → ORPEX |

La base se crée vide : au démarrage, ORPEX crée et met à jour ses tables (Flyway).

```sql
CREATE ROLE orpex LOGIN PASSWORD 'mot-de-passe-solide';
CREATE DATABASE orpex OWNER orpex;
```

## 2. Construire

Sur un poste de développement, à la racine du dépôt :

```powershell
.\scripts\build.ps1                  # tests complets, puis backend\target\orpex-backend-<version>.jar
docker build -t orpex:0.1.0 .        # ou l'image Docker (sans relancer les tests)
```

## 3. Configurer

Toute la configuration passe par des variables d'environnement : partir de [`orpex.env.example`](orpex.env.example).

| Variable | Rôle |
| --- | --- |
| `ORPEX_DB_URL`, `ORPEX_DB_USER`, `ORPEX_DB_PASSWORD` | Connexion PostgreSQL |
| `ORPEX_SECRET_KEY` | Clé de chiffrement du mot de passe SMTP (32 octets en Base64), **propre à l'installation** |
| `ORPEX_APP_URL` | Adresse de l'application pour les liens des mails |
| `ORPEX_ADMIN_EMAIL`, `ORPEX_ADMIN_PASSWORD` | Premier administrateur, créé seulement si aucun compte n'existe |
| `ORPEX_TIMEZONE` | Fuseau de l'installation (`Africa/Bamako` pour le pilote) |
| `ORPEX_COOKIE_SECURE` | `true` derrière un proxy HTTPS (automatique avec le profil `tls`) |
| `ORPEX_FORWARD_HEADERS` | `native` derrière un proxy HTTPS |
| `SPRING_PROFILES_ACTIVE`, `ORPEX_TLS_CERTIFICATE`, `ORPEX_TLS_PRIVATE_KEY` | HTTPS servi par ORPEX (voir plus bas) |
| `ORPEX_PORT` | Port (8080 en HTTP, 8443 avec le profil `tls`) |

Tout réglage de `application.properties` se surcharge aussi par une variable : nom en majuscules, points et tirets
remplacés par « _ » (par exemple `orpex.validation-cutoff-minutes` → `ORPEX_VALIDATION_CUTOFF_MINUTES`). Aucun fichier de
l'application n'est à modifier. Au démarrage, ORPEX signale dans ses journaux (« Configuration de production : … ») une
clé de chiffrement absente, des liens de mails en HTTP ou un cookie de session non réservé à HTTPS.

### HTTPS

Obligatoire en production (mots de passe et données des relances). Deux façons, au choix de la banque :

- **Derrière le proxy HTTPS de la banque** (recommandé s'il existe) : le proxy reçoit le HTTPS et transmet à ORPEX en
  HTTP sur le réseau interne. Régler `ORPEX_FORWARD_HEADERS=native` et `ORPEX_COOKIE_SECURE=true`, et faire en sorte que
  seul le proxy puisse joindre le port d'ORPEX.
- **ORPEX sert lui-même le HTTPS** : `SPRING_PROFILES_ACTIVE=tls`, avec le certificat du serveur et sa clé privée au
  format PEM, délivrés par l'autorité de certification interne :
  `ORPEX_TLS_CERTIFICATE=file:/etc/orpex/tls/orpex.crt` et `ORPEX_TLS_PRIVATE_KEY=file:/etc/orpex/tls/orpex.key` (clé
  non chiffrée, lisible seulement par le compte du service). Port 8443 par défaut ; TLS 1.2 et 1.3 seulement.

Dans les deux cas : `ORPEX_APP_URL=https://…`. Le navigateur reçoit l'en-tête HSTS (HTTPS imposé ensuite).

Générer la clé de chiffrement, et la conserver en lieu sûr (sans elle, le mot de passe SMTP est à ressaisir) :

```bash
openssl rand -base64 32
```

```powershell
$b = New-Object byte[] 32; [Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($b); [Convert]::ToBase64String($b)
```

Le premier administrateur reçoit un mot de passe provisoire : il doit le changer à sa première connexion. Retirer
ensuite `ORPEX_ADMIN_PASSWORD` de la configuration.

## 4a. Docker

```bash
cd deploy/docker
cp ../orpex.env.example .env     # remplir ; ORPEX_DB_URL=jdbc:postgresql://postgres:5432/orpex
docker compose up -d --build
docker compose ps                # orpex doit passer « healthy »
```

Les données sont dans le volume `orpex-data`. Mise à jour : reconstruire l'image, puis `docker compose up -d`.
Avec le profil `tls`, monter le dossier des certificats dans le conteneur (par exemple
`volumes: ["/etc/orpex/tls:/etc/orpex/tls:ro"]` sous le service `orpex`) et publier le port 8443.

## 4b. Service Linux (systemd)

```bash
sudo useradd --system --home /opt/orpex --shell /usr/sbin/nologin orpex
sudo mkdir -p /opt/orpex /etc/orpex
sudo cp orpex-backend-0.1.0-SNAPSHOT.jar /opt/orpex/orpex.jar
sudo cp orpex.env.example /etc/orpex/orpex.env          # remplir
sudo chown -R orpex:orpex /opt/orpex
sudo chown root:orpex /etc/orpex/orpex.env && sudo chmod 640 /etc/orpex/orpex.env
sudo cp linux/orpex.service /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable --now orpex
journalctl -u orpex -f                                   # journaux
```

## 4c. Service Windows (WinSW)

1. Télécharger [WinSW 2.12.0](https://github.com/winsw/winsw/releases/tag/v2.12.0) (`WinSW-x64.exe`).
2. Dans `C:\ORPEX` : `orpex.jar`, `WinSW-x64.exe` renommé `orpex-service.exe`, et
   [`windows/orpex-service.xml`](windows/orpex-service.xml), dont on remplit les variables.
3. Réserver le dossier aux administrateurs (le fichier XML contient des secrets).
4. Dans une invite PowerShell en administrateur :

```powershell
cd C:\ORPEX
.\orpex-service.exe install
.\orpex-service.exe start
.\orpex-service.exe status
```

Journaux : `C:\ORPEX\logs`. Le service redémarre seul après une panne et au démarrage de Windows.

## 5. Vérifier

- `https://<serveur>/actuator/health` répond `{"status":"UP"}` (ou `http://<serveur>:8080/…` avant la mise en HTTPS).
- Les journaux de démarrage ne contiennent aucun avertissement « Configuration de production ».
- Se connecter avec le premier administrateur, changer son mot de passe.
- Écran « Messagerie » : saisir le relais, « Contrôler maintenant », puis « Envoyer un mail de test ».
- Écran « Jours fériés » : ajouter les jours fériés du Mali de l'année (et les fêtes à date annoncée).

## 6. Exploiter

- **Journaux** : sur la sortie standard, repris par Docker (`docker compose logs`), systemd (`journalctl -u orpex`)
  ou WinSW (`C:\ORPEX\logs`). Avec `ORPEX_LOG_FORMAT=ecs` (réglage des modèles), une ligne JSON par événement, prête pour
  un outil de collecte ; vide, du texte lisible. Ils ne contiennent ni mot de passe ni contenu de message, et désignent
  les utilisateurs par leur numéro de compte ; le détail (qui, quoi, quand) est dans le journal d'audit de l'application.

- **Arrêt** : le service termine les requêtes et un envoi en cours (30 s au plus) avant de s'arrêter. Un arrêt brutal
  pendant un envoi le met « à vérifier » : il n'est jamais renvoyé automatiquement.
- **Pendant un arrêt** : les envois dont l'heure est passée de plus de 15 min deviennent « manqués », sans rattrapage.
- **Sauvegarde** : sauvegarder la base chaque jour (`pg_dump -Fc orpex > orpex-AAAAMMJJ.dump`) et conserver
  `ORPEX_SECRET_KEY` à part. Le journal d'audit et les envois ne sont jamais effacés par l'application.
- **Mise à jour** : arrêter le service, remplacer le JAR (ou l'image), redémarrer ; les migrations de base sont
  appliquées au démarrage.
