[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$MetadataPath
)

$ErrorActionPreference = 'Stop'
$repositoryRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..\..\..'))
if (-not (Test-Path -LiteralPath (Join-Path $repositoryRoot 'gradlew.bat') -PathType Leaf)) {
    throw "저장소 루트를 찾을 수 없습니다: $repositoryRoot"
}

$resolvedMetadata = (Resolve-Path -LiteralPath $MetadataPath -ErrorAction Stop).Path
$expectedOutputRoot = [System.IO.Path]::GetFullPath((Join-Path $repositoryRoot 'build\benchmark')) +
    [System.IO.Path]::DirectorySeparatorChar
$metadataFile = Get-Item -LiteralPath $resolvedMetadata
if (-not $resolvedMetadata.StartsWith($expectedOutputRoot, [System.StringComparison]::OrdinalIgnoreCase) -or
    $metadataFile.Name -ne 'metadata.json' -or $metadataFile.Directory.Name -notlike 'api-*') {
    throw "이 시작 스크립트의 build\benchmark\api-*\metadata.json만 사용할 수 있습니다: $resolvedMetadata"
}

$metadata = Get-Content -LiteralPath $resolvedMetadata -Raw -Encoding UTF8 | ConvertFrom-Json
if ($metadata.FormatVersion -ne 1 -or $metadata.Purpose -ne 'week4-r01-product-list-benchmark-api' -or
    $metadata.Port -ne 18080 -or $metadata.DatabaseSchema -ne 'week4_r01_index_benchmark' -or
    -not [string]::Equals($metadata.RepositoryRoot, $repositoryRoot, [System.StringComparison]::OrdinalIgnoreCase) -or
    -not [string]::Equals($metadata.MetadataPath, $resolvedMetadata, [System.StringComparison]::OrdinalIgnoreCase)) {
    throw '실험 API 실행 정보가 현재 저장소·스키마·포트와 일치하지 않습니다. 아무 프로세스도 종료하지 않았습니다.'
}

$targetProcessId = 0
if (-not [int]::TryParse([string]$metadata.ProcessId, [ref]$targetProcessId) -or $targetProcessId -le 0) {
    throw '실행 정보의 ProcessId가 올바르지 않습니다.'
}

$processInfo = Get-CimInstance -ClassName Win32_Process -Filter "ProcessId = $targetProcessId" -ErrorAction Stop
if ($null -eq $processInfo) {
    $metadata.Status = 'AlreadyStopped'
    $metadata.TcpListening = $false
    $metadata.StoppedAtUtc = [DateTime]::UtcNow.ToString('o')
    $metadata | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath $resolvedMetadata -Encoding UTF8
    Write-Host "이미 종료된 API입니다. PID=$targetProcessId"
    $metadata
    return
}

if ($processInfo.Name -ne 'java.exe' -or
    -not [string]::Equals($processInfo.ExecutablePath, $metadata.ExecutablePath,
        [System.StringComparison]::OrdinalIgnoreCase) -or
    -not [string]::Equals($processInfo.CommandLine, $metadata.CommandLine, [System.StringComparison]::Ordinal) -or
    $processInfo.CreationDate.ToUniversalTime().Ticks -ne ([DateTimeOffset]$metadata.ProcessCreationUtc).UtcDateTime.Ticks -or
    [string]::IsNullOrWhiteSpace($metadata.JarPath) -or
    $processInfo.CommandLine.IndexOf($metadata.JarPath, [System.StringComparison]::OrdinalIgnoreCase) -lt 0 -or
    $processInfo.CommandLine -notmatch '(?:^|\s)-jar\s+' -or
    $processInfo.CommandLine -notmatch '(?:^|\s)--server\.port=18080(?:\s|$)' -or
    @($metadata.ApplicationArguments) -notcontains '--server.port=18080') {
    throw "PID $targetProcessId 의 Java 경로·JAR·명령·생성 시각·포트가 일치하지 않아 종료하지 않았습니다."
}

$listeners = @(Get-NetTCPConnection -State Listen -ErrorAction Stop |
    Where-Object { $_.LocalPort -eq 18080 })
if (@($listeners | Where-Object { $_.OwningProcess -ne $targetProcessId }).Count -gt 0) {
    throw '18080 포트를 다른 프로세스가 사용하고 있어 종료하지 않았습니다.'
}

# PID 재사용을 한 번 더 확인한 뒤 메타데이터의 Java 프로세스만 종료한다.
$confirmedInfo = Get-CimInstance -ClassName Win32_Process -Filter "ProcessId = $targetProcessId" -ErrorAction Stop
if ($null -ne $confirmedInfo) {
    if ($confirmedInfo.CreationDate.ToUniversalTime().Ticks -ne
        ([DateTimeOffset]$metadata.ProcessCreationUtc).UtcDateTime.Ticks -or
        -not [string]::Equals($confirmedInfo.CommandLine, $metadata.CommandLine, [System.StringComparison]::Ordinal)) {
        throw '종료 직전 프로세스 정보가 달라져 아무 프로세스도 종료하지 않았습니다.'
    }
    $targetProcess = Get-Process -Id $targetProcessId -ErrorAction Stop
    Stop-Process -InputObject $targetProcess -Force -ErrorAction Stop
    Wait-Process -Id $targetProcessId -Timeout 10 -ErrorAction SilentlyContinue
}

$metadata.Status = 'Stopped'
$metadata.TcpListening = $false
$metadata.StoppedAtUtc = [DateTime]::UtcNow.ToString('o')
$metadata | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath $resolvedMetadata -Encoding UTF8
Write-Host "확인된 실험 API만 종료했습니다. PID=$targetProcessId"
$metadata
