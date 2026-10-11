# Construit l'application complète : le front Angular est copié dans le JAR Spring Boot.
# Prérequis : JDK 21, Maven 3.9+, Node 22.22.3+ ou 24.15+.
$ErrorActionPreference = 'Stop'
$racine = Split-Path -Parent $PSScriptRoot

Push-Location "$racine\frontend"
npm ci
npm run build
Pop-Location

$statique = "$racine\backend\src\main\resources\static"
if (Test-Path $statique) { Remove-Item $statique -Recurse -Force }
New-Item -ItemType Directory -Path $statique | Out-Null
Copy-Item "$racine\frontend\dist\frontend\browser\*" $statique -Recurse

Push-Location "$racine\backend"
mvn -B verify
Pop-Location

Write-Host "Terminé : backend\target\orpex-backend-0.1.0-SNAPSHOT.jar"
