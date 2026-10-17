# Essayer ORPEX sur son PC (Windows)

Pour tester l'application, sans rien installer d'autre que Docker Desktop. Les données restent sur ce PC.

## Installer et lancer

1. Installer **Docker Desktop** : https://www.docker.com/products/docker-desktop/ (redémarrer le PC si demandé), puis le
   démarrer et attendre qu'il indique « Engine running ».
2. Télécharger le fichier **`Installer-ORPEX.bat`** :
   https://github.com/mdao032/orpex/raw/main/Installer-ORPEX.bat (si le navigateur avertit, choisir « Conserver »).
3. Double-cliquer dessus.
   - Si Windows affiche « Windows a protégé votre ordinateur » : **Informations complémentaires** → **Exécuter quand
     même**.
   - Il télécharge ORPEX dans le dossier `ORPEX` du profil (`C:\Users\<nom>\ORPEX`), crée sur le Bureau les raccourcis
     **Essayer ORPEX** et **Arrêter ORPEX**, puis lance l'application. La première fois, comptez plusieurs minutes.
4. Le navigateur s'ouvre sur ORPEX. L'identifiant et le mot de passe s'affichent dans la fenêtre (et dans
   `ORPEX\identifiants-essai.txt`) : à la première connexion, ORPEX demande de choisir un nouveau mot de passe.

Les fois suivantes : raccourci **Essayer ORPEX** du Bureau (Docker Desktop démarré). Pour arrêter : raccourci **Arrêter
ORPEX** ; les données sont conservées.

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
- Tout effacer : dans `ORPEX\deploy\docker`, ouvrir un terminal et lancer `docker compose -p orpex-essai down -v`,
  puis supprimer le dossier `ORPEX` et les deux raccourcis du Bureau.
- Sans le fichier d'installation : télécharger le ZIP du projet (GitHub, bouton **Code** → **Download ZIP**), l'extraire
  et double-cliquer sur `Essayer-ORPEX.bat`.
