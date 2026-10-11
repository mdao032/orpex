# Lance l'environnement de développement : base + faux serveur de mail (Docker), puis l'API.
# Dans un second terminal : cd frontend ; npm install ; npm start   (http://localhost:4200)
$ErrorActionPreference = 'Stop'
$racine = Split-Path -Parent $PSScriptRoot

docker compose -f "$racine\docker-compose.yml" up -d --wait

Push-Location "$racine\backend"
mvn spring-boot:run "-Dspring-boot.run.profiles=dev"
Pop-Location
