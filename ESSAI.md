# Essayer ORPEX sur son PC (Windows)

Pour tester l'application, sans rien installer d'autre que Docker Desktop. Les données restent sur ce PC.

## Une seule fois

1. Installer **Docker Desktop** : https://www.docker.com/products/docker-desktop/ (redémarrer le PC si demandé).
2. Télécharger ORPEX : sur la page GitHub du projet, bouton vert **Code** → **Download ZIP**, puis extraire le ZIP
   (clic droit → Extraire tout).

## Lancer

1. Démarrer **Docker Desktop** et attendre qu'il indique « Engine running ».
2. Dans le dossier extrait, double-cliquer sur **`Essayer-ORPEX.bat`**.
   - Si Windows affiche « Windows a protégé votre ordinateur » : **Informations complémentaires** → **Exécuter quand
     même**.
   - La première fois, la préparation prend plusieurs minutes ; les fois suivantes, quelques secondes.
3. Le navigateur s'ouvre sur ORPEX. L'identifiant et le mot de passe s'affichent dans la fenêtre (et dans le fichier
   `identifiants-essai.txt`) : à la première connexion, ORPEX demande de choisir un nouveau mot de passe.

Pour arrêter : double-cliquer sur **`Arreter-ORPEX.bat`**. Les données sont conservées pour la fois suivante.

## Premiers réglages dans ORPEX (menu administrateur)

- **Messagerie** : le compte qui enverra les mails. Pour un essai avec Gmail : serveur `smtp.gmail.com`, port `587`,
  STARTTLS, utilisateur = l'adresse Gmail complète, mot de passe = un **mot de passe d'application** Google (Compte Google
  → Sécurité → validation en deux étapes activée → Mots de passe des applications ; 16 lettres, sans espaces), adresse
  expéditrice = la même adresse Gmail. Puis **Contrôler maintenant** et **Envoyer un mail de test**.
- **Jours fériés** : ajouter les jours fériés du Mali de l'année.
- **Comptes** : créer d'autres utilisateurs si besoin.

## À savoir

- Les heures (fichier Excel et écrans) sont celles de **Bamako**, pas celles du PC.
- Le modèle Excel se télécharge depuis la page **Importer un fichier**.
- Les mails partent réellement : utiliser des données fictives et des destinataires prévenus.
- Tout effacer pour repartir de zéro : dans le dossier `deploy\docker`, ouvrir un terminal et lancer
  `docker compose -p orpex-essai down -v`, puis supprimer `deploy\docker\.env` et `identifiants-essai.txt`.
