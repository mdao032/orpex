@echo off
rem Installe et lance ORPEX pour un essai sur ce PC (Docker Desktop requis). Double-cliquer sur ce fichier.
rem La suite du fichier est un script PowerShell, extrait puis execute.
set "ORPEX_PS=%TEMP%\orpex-installer.ps1"
powershell -NoProfile -ExecutionPolicy Bypass -Command "$s = Get-Content -Raw -Encoding UTF8 -LiteralPath '%~f0'; $i = $s.IndexOf('#' + 'PS-DEBUT'); [IO.File]::WriteAllText($env:ORPEX_PS, $s.Substring($i), (New-Object Text.UTF8Encoding $true))"
powershell -NoProfile -ExecutionPolicy Bypass -File "%ORPEX_PS%"
del "%ORPEX_PS%" >nul 2>&1
exit /b

#PS-DEBUT
$ErrorActionPreference = 'Continue'
# Dossier d'installation : ORPEX dans le profil de l'utilisateur (ORPEX_DOSSIER pour un autre emplacement).
$destination = if ($env:ORPEX_DOSSIER) { $env:ORPEX_DOSSIER } else { Join-Path $env:USERPROFILE 'ORPEX' }
$source = 'https://github.com/mdao032/orpex/archive/refs/heads/main.zip'

function Fin($code) {
    Write-Host ''
    Read-Host 'Appuyez sur Entrée pour fermer'
    exit $code
}

Write-Host 'Installation d''ORPEX pour un essai' -ForegroundColor Cyan
docker info *> $null
if ($LASTEXITCODE -ne 0) {
    Write-Host 'Docker Desktop n''est pas démarré (ou pas installé).' -ForegroundColor Red
    Write-Host 'Installez-le depuis la page qui va s''ouvrir, démarrez-le, attendez « Engine running »,'
    Write-Host 'puis relancez ce fichier.'
    Start-Process 'https://www.docker.com/products/docker-desktop/'
    Fin 1
}

if (Test-Path (Join-Path $destination 'Essayer-ORPEX.bat')) {
    Write-Host "ORPEX est déjà installé dans $destination." -ForegroundColor Green
} else {
    Write-Host 'Téléchargement d''ORPEX...'
    [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
    $zip = Join-Path $env:TEMP 'orpex-main.zip'
    $extrait = Join-Path $env:TEMP ('orpex-' + (Get-Random))
    try {
        Invoke-WebRequest $source -OutFile $zip -UseBasicParsing
        Expand-Archive -LiteralPath $zip -DestinationPath $extrait
        Move-Item (Join-Path $extrait 'orpex-main') $destination
    } catch {
        Write-Host "Téléchargement impossible : $($_.Exception.Message)" -ForegroundColor Red
        Fin 1
    } finally {
        Remove-Item $zip, $extrait -Recurse -Force -ErrorAction SilentlyContinue
    }
    Write-Host "ORPEX est installé dans $destination." -ForegroundColor Green
}

# Raccourcis sur le Bureau pour les fois suivantes.
$bureau = [Environment]::GetFolderPath('Desktop')
$shell = New-Object -ComObject WScript.Shell
foreach ($r in @(@('Essayer ORPEX', 'Essayer-ORPEX.bat'), @('Arrêter ORPEX', 'Arreter-ORPEX.bat'))) {
    $raccourci = $shell.CreateShortcut((Join-Path $bureau ($r[0] + '.lnk')))
    $raccourci.TargetPath = Join-Path $destination $r[1]
    $raccourci.WorkingDirectory = $destination
    $raccourci.Save()
}
Write-Host 'Raccourcis « Essayer ORPEX » et « Arrêter ORPEX » créés sur le Bureau.'
Write-Host ''

& powershell -NoProfile -ExecutionPolicy Bypass -File (Join-Path $destination 'scripts\essai.ps1')