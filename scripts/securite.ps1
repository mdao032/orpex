# Analyse de sécurité des dépendances : inventaires des composants (SBOM CycloneDX) et vulnérabilités connues.
# Outils : CycloneDX (Maven, via Spring Boot), Trivy (image Docker figée), npm audit.
# Prérequis : JDK 21, Maven, Node, Docker (image orpex:0.1.0 construite, sinon elle l'est ici).
# Résultats dans rapports-securite\ ; code de sortie 1 si une vulnérabilité haute ou critique est trouvée.
# Les outils téléchargent leurs bases de vulnérabilités : à lancer depuis un poste qui a accès à Internet.
# Les avertissements des outils (sortie d'erreur) ne sont pas des échecs : les codes de retour sont vérifiés un à un.
$ErrorActionPreference = 'Continue'
$racine = Split-Path -Parent $PSScriptRoot
$rapports = Join-Path $racine 'rapports-securite'
$trivy = 'aquasec/trivy:0.76.0'
New-Item -ItemType Directory -Force -Path $rapports | Out-Null
$problemes = @()

Write-Host '1/4 Inventaire Java (CycloneDX)'
mvn -B -q -f "$racine\backend" package -DskipTests
if ($LASTEXITCODE -ne 0) { throw 'Construction du backend en échec' }
Copy-Item "$racine\backend\target\classes\META-INF\sbom\application.cdx.json" "$rapports\orpex-backend-sbom.json"

Write-Host '2/4 Inventaire et audit du front (dépendances livrées seulement, d''après package-lock.json)'
docker run --rm -v "$racine\frontend:/front:ro" -v "${rapports}:/rapports" -v orpex-trivy-cache:/root/.cache $trivy `
    fs --quiet --format cyclonedx --output /rapports/orpex-frontend-sbom.json /front/package-lock.json 2>$null
if ($LASTEXITCODE -ne 0) { throw 'Inventaire du front en échec' }
Push-Location "$racine\frontend"
npm audit --omit=dev --audit-level=high 2>$null | Out-File -Encoding utf8 "$rapports\npm-audit.txt"
if ($LASTEXITCODE -ne 0) { $problemes += 'npm audit : vulnérabilité haute ou critique (voir npm-audit.txt)' }
Pop-Location

Write-Host '3/4 Image Docker (système, Java, bibliothèques)'
docker image inspect orpex:0.1.0 *> $null
if ($LASTEXITCODE -ne 0) { docker build -t orpex:0.1.0 $racine }
docker run --rm -v /var/run/docker.sock:/var/run/docker.sock -v orpex-trivy-cache:/root/.cache $trivy `
    image --quiet --severity HIGH,CRITICAL --exit-code 1 orpex:0.1.0 2>$null | Out-File -Encoding utf8 "$rapports\trivy-image.txt"
if ($LASTEXITCODE -ne 0) { $problemes += 'Trivy (image) : vulnérabilité haute ou critique (voir trivy-image.txt)' }

Write-Host '4/4 Inventaire Java analysé par Trivy'
docker run --rm -v "${rapports}:/rapports" -v orpex-trivy-cache:/root/.cache $trivy `
    sbom --quiet --severity HIGH,CRITICAL --exit-code 1 /rapports/orpex-backend-sbom.json 2>$null | Out-File -Encoding utf8 "$rapports\trivy-sbom-java.txt"
if ($LASTEXITCODE -ne 0) { $problemes += 'Trivy (SBOM Java) : vulnérabilité haute ou critique (voir trivy-sbom-java.txt)' }

if ($problemes.Count -gt 0) {
    $problemes | ForEach-Object { Write-Host "ATTENTION : $_" -ForegroundColor Red }
    exit 1
}
Write-Host "Aucune vulnérabilité haute ou critique. Rapports et SBOM : $rapports" -ForegroundColor Green
