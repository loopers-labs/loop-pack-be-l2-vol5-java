[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$JarPath,
    [string]$JavaHome = 'C:\Program Files\Java\jdk-21.0.10'
)

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'benchmark-common.ps1')
$repositoryRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..\..\..'))
$resolvedJar = (Resolve-Path -LiteralPath $JarPath).Path
$java = Join-Path $JavaHome 'bin\java.exe'
$fixture = Get-Content (Join-Path $PSScriptRoot 'expected-responses.json') -Raw -Encoding UTF8 | ConvertFrom-Json
$productIdValue = [long]$fixture.seed.expectedIds[0]
$expected = $fixture.responses.PSObject.Properties[[string]$productIdValue].Value
$directory = Join-Path $repositoryRoot ('build\benchmark\r02\http-sql-' + (Get-Date -Format 'yyyyMMdd-HHmmss-fffffff'))
[void](New-Item -ItemType Directory -Path $directory)
$records = [Collections.Generic.List[object]]::new()
$protected = @(Get-R02ProtectedListeners)
$owned = $null
$snapshotBefore = Read-R02Database 'docker-mysql-1' $directory 'before'

function ConvertTo-R02CanonicalJson {
    param($Value)
    if ($null -eq $Value) { return 'null' }
    if ($Value -is [pscustomobject]) {
        $parts = @($Value.PSObject.Properties | Sort-Object Name | ForEach-Object {
            (ConvertTo-Json -InputObject $_.Name -Compress) + ':' + (ConvertTo-R02CanonicalJson $_.Value)
        })
        return '{' + ($parts -join ',') + '}'
    }
    if ($Value -is [Array]) {
        return '[' + (@($Value | ForEach-Object { ConvertTo-R02CanonicalJson $_ }) -join ',') + ']'
    }
    return ConvertTo-Json -InputObject $Value -Compress
}

try {
    foreach ($modeValue in @('OFF', 'ON')) {
        $owned = Start-R02Api -RepositoryRoot $repositoryRoot -Java $java -Jar $resolvedJar `
            -CacheMode $modeValue -Directory (Join-Path $directory ($modeValue.ToLowerInvariant() + '-api')) -CaptureSql
        foreach ($requestNumber in @(1, 2)) {
            $label = $modeValue.ToLowerInvariant() + '-' + $requestNumber
            $metricsBefore = Read-R02Metrics $owned.Metadata (Join-Path $directory ($label + '-metrics-before.prom'))
            $lineCount = @(Get-Content -LiteralPath $owned.Metadata.StdoutPath -Encoding UTF8).Count
            $response = Invoke-WebRequest -Uri ("http://127.0.0.1:18081/api/v1/products/$productIdValue") `
                -UseBasicParsing -TimeoutSec 30
            $body = $(if ($response.Content -is [byte[]]) {
                [Text.Encoding]::UTF8.GetString($response.Content)
            } else { [string]$response.Content })
            $body | Set-Content -LiteralPath (Join-Path $directory ($label + '-response.json')) -Encoding UTF8
            $log = @(Get-Content -LiteralPath $owned.Metadata.StdoutPath -Encoding UTF8 | Select-Object -Skip $lineCount)
            [IO.File]::WriteAllLines((Join-Path $directory ($label + '-sql.log')), [string[]]$log,
                [Text.UTF8Encoding]::new($false))
            $sqlCount = @($log | Where-Object { $_ -match 'org\.hibernate\.SQL.*\bselect\b' }).Count
            $actual = $body | ConvertFrom-Json
            $equal = (ConvertTo-R02CanonicalJson $actual) -ceq (ConvertTo-R02CanonicalJson $expected)
            $metricsAfter = Read-R02Metrics $owned.Metadata (Join-Path $directory ($label + '-metrics-after.prom'))
            $delta = Get-R02MetricDelta $metricsBefore $metricsAfter 1
            $expectedSql = $(if ($modeValue -eq 'ON' -and $requestNumber -eq 2) { 0 } else { 1 })
            $record = [pscustomobject][ordered]@{
                CacheMode = $modeValue; RequestNumber = $requestNumber; ProductId = $productIdValue
                StatusCode = [int]$response.StatusCode; SqlStatements = $sqlCount; ExpectedSqlStatements = $expectedSql
                WholeResponseMatches = $equal; Metrics = $delta
                ResponseFile = $label + '-response.json'; SqlFile = $label + '-sql.log'
            }
            [void]$records.Add($record)
            if ($response.StatusCode -ne 200 -or -not $equal -or $sqlCount -ne $expectedSql -or $delta.DbLoads -ne $expectedSql) {
                throw "HTTP/SQL/DB load comparison failed: $label"
            }
        }
        Stop-R02OwnedJava $owned.Metadata
        $owned = $null
    }
} finally {
    if ($null -ne $owned) { Stop-R02OwnedJava $owned.Metadata }
    $snapshotAfter = Read-R02Database 'docker-mysql-1' $directory 'after'
    $protectedAfter = @(Get-R02ProtectedListeners)
    $preserved = ($protected | ConvertTo-Json -Compress) -ceq ($protectedAfter | ConvertTo-Json -Compress)
    $summary = [pscustomobject][ordered]@{
        JarName = [IO.Path]::GetFileName($resolvedJar); JarSha256 = (Get-FileHash $resolvedJar -Algorithm SHA256).Hash
        CapturedAtUtc = [DateTime]::UtcNow.ToString('o'); Requests = @($records.ToArray())
        DatabaseBefore = $snapshotBefore; DatabaseAfter = $snapshotAfter; ProtectedListenersPreserved = $preserved
    }
    Write-R02Json $summary (Join-Path $directory 'summary.json')
    Assert-R02PortsFree
    if (-not $preserved) { throw 'Protected API listeners changed during HTTP collection.' }
}
Write-Host "R02 HTTP/SQL evidence: $directory"
$directory
