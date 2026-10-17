# Essai d'ORPEX sur un PC Windows avec Docker Desktop : configuration générée, application lancée, navigateur ouvert.
# Lancé par Essayer-ORPEX.bat (double-clic). Pour un essai seulement : la production suit deploy/README.md.
param([switch]$Arreter)
$ErrorActionPreference = 'Continue'
$racine = Split-Path -Parent $PSScriptRoot
$dossier = Join-Path $racine 'deploy\docker'
$env_ = Join-Path $dossier '.env'
$identifiants = Join-Path $racine 'identifiants-essai.txt'

function Pause-Fin($code) {
    Write-Host ''
    Read-Host 'Appuyez sur Entrée pour fermer'
    exit $code
}

docker info *> $null
if ($LASTEXITCODE -ne 0) {
    Write-Host "Docker ne répond pas. Installez Docker Desktop (https://www.docker.com/products/docker-desktop/)," -ForegroundColor Red
    Write-Host "démarrez-le, attendez qu'il indique « Engine running », puis relancez ce fichier." -ForegroundColor Red
    Pause-Fin 1
}

Push-Location $dossier
if ($Arreter) {
    cmd /c "docker compose -p orpex-essai stop 2>&1"
    Write-Host 'ORPEX est arrêté. Vos données sont conservées ; relancez Essayer-ORPEX.bat pour reprendre.' -ForegroundColor Green
    Pop-Location
    Pause-Fin 0
}

function Secret($octets) {
    $b = New-Object byte[] $octets
    [Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($b)
    [Convert]::ToBase64String($b)
}

if (-not (Test-Path $env_)) {
    # Premier lancement : port libre, secrets aléatoires, administrateur d'essai.
    $port = 8080
    while (Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue) { $port++ }
    $motDePasse = 'Essai-' + ((Secret 9) -replace '[+/=]', 'x')
    @"
ORPEX_HOST_PORT=$port
ORPEX_DB_URL=jdbc:postgresql://postgres:5432/orpex
ORPEX_DB_USER=orpex
ORPEX_DB_PASSWORD=$((Secret 18) -replace '[+/=]', 'x')
ORPEX_SECRET_KEY=$(Secret 32)
ORPEX_APP_URL=http://localhost:$port
ORPEX_ADMIN_EMAIL=admin@essai.local
ORPEX_ADMIN_PASSWORD=$motDePasse
ORPEX_TIMEZONE=Africa/Bamako
ORPEX_FORWARD_HEADERS=none
ORPEX_COOKIE_SECURE=false
ORPEX_LOG_FORMAT=
ORPEX_ENVIRONMENT=essai
"@ | Out-File -Encoding ascii $env_
    @"
ORPEX - essai local
Adresse         : http://localhost:$port
Identifiant     : admin@essai.local
Mot de passe    : $motDePasse   (provisoire : ORPEX demande d'en choisir un autre à la première connexion)
"@ | Out-File -Encoding utf8 $identifiants
}
$port = ((Get-Content $env_ | Select-String '^ORPEX_HOST_PORT=') -replace 'ORPEX_HOST_PORT=', '').Trim()
$url = "http://localhost:$port"

Write-Host 'Démarrage d''ORPEX (la première fois, la construction prend plusieurs minutes)...' -ForegroundColor Cyan
# Via cmd : la progression de Docker (sortie d'erreur) ne s'affiche pas en rouge comme une erreur.
cmd /c "docker compose -p orpex-essai up -d --build 2>&1"
if ($LASTEXITCODE -ne 0) {
    Write-Host 'Le démarrage a échoué : voir les messages ci-dessus.' -ForegroundColor Red
    Pop-Location
    Pause-Fin 1
}

Write-Host 'Attente de l''application...' -ForegroundColor Cyan
$pret = $false
for ($i = 0; $i -lt 60; $i++) {
    Start-Sleep -Seconds 5
    if ((docker inspect -f '{{.State.Health.Status}}' orpex-essai-orpex-1 2>$null) -eq 'healthy') { $pret = $true; break }
}
Pop-Location
if (-not $pret) {
    Write-Host "ORPEX ne répond pas encore. Détail : docker compose -p orpex-essai logs orpex (dans deploy\docker)." -ForegroundColor Red
    Pause-Fin 1
}

Start-Process $url
Write-Host ''
Write-Host 'ORPEX est prêt.' -ForegroundColor Green
Get-Content $identifiants | ForEach-Object { Write-Host $_ }
Write-Host "(aussi dans $identifiants)"
Write-Host ''
Write-Host 'Pour arrêter : double-cliquer sur Arreter-ORPEX.bat.'
Pause-Fin 0
