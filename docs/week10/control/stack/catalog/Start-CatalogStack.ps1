[CmdletBinding()]
param([string] $JarVersion, [switch] $Incident)

$ErrorActionPreference = 'Stop'
$base = (Get-Item -LiteralPath (Join-Path $PSScriptRoot '..')).FullName
$repo = (Get-Item -LiteralPath (Join-Path $base '../../../..')).FullName
if (-not $JarVersion) { $JarVersion = (git -C $repo rev-parse --short=7 HEAD).Trim() }
foreach ($app in 'commerce-api', 'test-pg') {
    if (-not (Test-Path -LiteralPath (Join-Path $repo "apps/$app/build/libs/$app-$JarVersion.jar") -PathType Leaf)) {
        throw "Build :apps:commerce-api:bootJar and :apps:test-pg:bootJar for $JarVersion."
    }
}
$env:W10_JAR_VERSION = $JarVersion
$project = if ($Incident) { 'week10-catalog-incident' } else { 'week10-catalog-stack' }
$ports = if ($Incident) { 'incident.env' } else { 'catalog.env' }
$flags = @('-p', $project, '--env-file', (Join-Path $PSScriptRoot $ports),
           '-f', (Join-Path $base 'compose.yaml'), '-f', (Join-Path $PSScriptRoot 'compose.yaml'))
docker compose @flags up -d
if ($LASTEXITCODE -ne 0) { throw 'Catalog application stack startup failed.' }
