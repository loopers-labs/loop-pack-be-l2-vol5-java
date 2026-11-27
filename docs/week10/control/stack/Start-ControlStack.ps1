[CmdletBinding()]
param([string] $JarVersion)

$ErrorActionPreference = 'Stop'
$repo = (Get-Item -LiteralPath (Join-Path $PSScriptRoot '../../../..')).FullName
if (-not $JarVersion) {
    $JarVersion = (git -C $repo rev-parse --short=7 HEAD).Trim()
}
foreach ($app in 'commerce-api', 'test-pg') {
    $jar = Join-Path $repo "apps/$app/build/libs/$app-$JarVersion.jar"
    if (-not (Test-Path -LiteralPath $jar -PathType Leaf)) {
        throw "Build :apps:commerce-api:bootJar and :apps:test-pg:bootJar for this checkout. Missing $jar"
    }
}

$env:W10_JAR_VERSION = $JarVersion
$compose = Join-Path $PSScriptRoot 'compose.yaml'
docker compose -f $compose up -d
if ($LASTEXITCODE -ne 0) { throw 'Control stack startup failed.' }

$health = 'http://127.0.0.1:18182/actuator/health'
$until = [DateTime]::UtcNow.AddMinutes(3)
while ([DateTime]::UtcNow -lt $until) {
    try {
        if ((Invoke-RestMethod -Uri $health -TimeoutSec 4).status -eq 'UP') {
            Write-Output 'Commerce ready: http://127.0.0.1:18180   Metrics: http://127.0.0.1:18182/actuator/prometheus'
            return
        }
    } catch [System.Net.WebException] {
        Write-Verbose $_
    } catch [System.Net.Http.HttpRequestException] {
        Write-Verbose $_
    }
    Start-Sleep -Seconds 2
}
throw "Commerce health did not reach UP. Inspect docker compose -f $compose logs commerce pg"
