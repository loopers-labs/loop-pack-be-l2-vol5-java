[CmdletBinding()]
param(
    [string]$JavaHome = 'C:\Program Files\Java\jdk-21.0.10',
    [string]$JarPath,
    [switch]$CaptureSql
)

$ErrorActionPreference = 'Stop'
$repositoryRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..\..\..'))
if (-not (Test-Path -LiteralPath (Join-Path $repositoryRoot 'gradlew.bat') -PathType Leaf)) {
    throw "저장소 루트를 찾을 수 없습니다: $repositoryRoot"
}

$javaExecutable = [System.IO.Path]::GetFullPath((Join-Path $JavaHome 'bin\java.exe'))
if (-not (Test-Path -LiteralPath $javaExecutable -PathType Leaf)) {
    throw "Java 실행 파일을 찾을 수 없습니다: $javaExecutable"
}

if ([string]::IsNullOrWhiteSpace($JarPath)) {
    $jarDirectory = Join-Path $repositoryRoot 'apps\commerce-api\build\libs'
    $selectedJar = Get-ChildItem -LiteralPath $jarDirectory -Filter 'commerce-api-*.jar' -File |
        Where-Object { $_.Name -notlike '*-plain.jar' } |
        Sort-Object LastWriteTimeUtc, Name -Descending |
        Select-Object -First 1
    if ($null -eq $selectedJar) {
        throw "실행할 commerce-api JAR이 없습니다: $jarDirectory"
    }
    $JarPath = $selectedJar.FullName
} elseif (-not [System.IO.Path]::IsPathRooted($JarPath)) {
    $JarPath = Join-Path $repositoryRoot $JarPath
}

$resolvedJar = [System.IO.Path]::GetFullPath($JarPath)
if (-not (Test-Path -LiteralPath $resolvedJar -PathType Leaf) -or
    [System.IO.Path]::GetExtension($resolvedJar) -ne '.jar' -or
    (Get-Item -LiteralPath $resolvedJar).Length -eq 0) {
    throw "실행 가능한 JAR 경로가 아닙니다: $resolvedJar"
}

Add-Type -AssemblyName System.IO.Compression.FileSystem
$archive = $null
try {
    $archive = [System.IO.Compression.ZipFile]::OpenRead($resolvedJar)
    if ($null -eq $archive.GetEntry('BOOT-INF/classes/com/loopers/CommerceApiApplication.class')) {
        throw 'CommerceApiApplication이 들어 있는 Spring Boot JAR이 아닙니다.'
    }
} catch {
    throw "commerce-api JAR 확인 실패 ($resolvedJar): $($_.Exception.Message)"
} finally {
    if ($null -ne $archive) {
        $archive.Dispose()
    }
}

$port = 18080
$existingListeners = @(Get-NetTCPConnection -State Listen -ErrorAction Stop |
    Where-Object { $_.LocalPort -eq $port })
if ($existingListeners.Count -gt 0) {
    $owners = ($existingListeners.OwningProcess | Sort-Object -Unique) -join ', '
    throw "TCP $port 포트가 이미 사용 중입니다 (PID: $owners). 기존 프로세스는 변경하지 않았습니다."
}

$databaseUrl = 'jdbc:mysql://localhost:3306/week4_r01_index_benchmark?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true'
$sqlLevel = 'OFF'
$bindLevel = 'OFF'
if ($CaptureSql) {
    $sqlLevel = 'DEBUG'
    $bindLevel = 'TRACE'
}

$jvmArguments = @('-Dfile.encoding=UTF-8')
$applicationArguments = @(
    '--spring.profiles.active=local'
    '--spring.main.web-application-type=servlet'
    '--server.address=127.0.0.1'
    '--server.port=18080'
    "--datasource.mysql-jpa.main.jdbc-url=$databaseUrl"
    '--datasource.mysql-jpa.main.username=application'
    '--datasource.mysql-jpa.main.password=application'
    '--spring.jpa.hibernate.ddl-auto=none'
    '--spring.jpa.show-sql=false'
    '--spring.jpa.properties.hibernate.jdbc.time_zone=UTC'
    '--benchmark.seed.enabled=false'
    '--scheduler.like-count.enabled=false'
    '--query.product-count-cache.ttl=0s'
    '--management.health.redis.enabled=false'
    "--logging.level.org.hibernate.SQL=$sqlLevel"
    "--logging.level.org.hibernate.orm.jdbc.bind=$bindLevel"
)

function ConvertTo-NativeArgument {
    param([string]$Value)

    if ($Value.Length -gt 0 -and $Value -notmatch '[\s"]') {
        return $Value
    }
    $escaped = [regex]::Replace($Value, '(\\*)"', '$1$1\"')
    $escaped = [regex]::Replace($escaped, '(\\+)$', '$1$1')
    return '"' + $escaped + '"'
}

$javaArguments = @($jvmArguments) + @('-jar', $resolvedJar) + @($applicationArguments)
$nativeArgumentLine = ($javaArguments | ForEach-Object { ConvertTo-NativeArgument $_ }) -join ' '
$sessionName = 'api-' + (Get-Date -Format 'yyyyMMdd-HHmmss-fffffff') + '-' + [guid]::NewGuid().ToString('N').Substring(0, 8)
$sessionDirectory = Join-Path (Join-Path $repositoryRoot 'build\benchmark') $sessionName
[void](New-Item -ItemType Directory -Path $sessionDirectory)
$stdoutPath = Join-Path $sessionDirectory 'stdout.log'
$stderrPath = Join-Path $sessionDirectory 'stderr.log'
$metadataPath = Join-Path $sessionDirectory 'metadata.json'

Write-Host "조회 API 시작: $resolvedJar"
Write-Host "모드: $(if ($CaptureSql) { 'SQL 수집' } else { 'JMeter 성능 측정' }), 포트: $port"
$process = Start-Process -FilePath $javaExecutable -ArgumentList $nativeArgumentLine -WorkingDirectory $repositoryRoot `
    -WindowStyle Hidden -RedirectStandardOutput $stdoutPath -RedirectStandardError $stderrPath -PassThru
$startupTimer = [System.Diagnostics.Stopwatch]::StartNew()
$processInfo = Get-CimInstance -ClassName Win32_Process -Filter "ProcessId = $($process.Id)" -ErrorAction Stop
if ($null -eq $processInfo -or [string]::IsNullOrWhiteSpace($processInfo.CommandLine)) {
    throw "Java 프로세스 정보를 확인하지 못했습니다. PID=$($process.Id), 로그=$sessionDirectory"
}

$metadata = [pscustomobject][ordered]@{
    FormatVersion = 1
    Purpose = 'week4-r01-product-list-benchmark-api'
    RepositoryRoot = $repositoryRoot
    ProcessId = [int]$process.Id
    ProcessCreationUtc = $processInfo.CreationDate.ToUniversalTime().ToString('o')
    ExecutablePath = $javaExecutable
    JarPath = $resolvedJar
    CommandLine = $processInfo.CommandLine
    NativeArgumentLine = $nativeArgumentLine
    JvmArguments = $jvmArguments
    ApplicationArguments = $applicationArguments
    Port = $port
    Profile = 'local'
    DatabaseSchema = 'week4_r01_index_benchmark'
    DatabaseUrl = $databaseUrl
    CaptureSql = [bool]$CaptureSql
    Status = 'Starting'
    TcpListening = $false
    CreatedAtUtc = [DateTime]::UtcNow.ToString('o')
    StoppedAtUtc = $null
    MetadataPath = $metadataPath
    StdoutPath = $stdoutPath
    StderrPath = $stderrPath
}
$metadata | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath $metadataPath -Encoding UTF8

while ($startupTimer.Elapsed.TotalSeconds -lt 45) {
    $process.Refresh()
    if ($process.HasExited) {
        $metadata.Status = 'ExitedDuringStartup'
        $metadata | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath $metadataPath -Encoding UTF8
        throw "API가 시작 도중 종료됐습니다 (exit=$($process.ExitCode)). 로그=$sessionDirectory, metadata=$metadataPath"
    }

    $client = [System.Net.Sockets.TcpClient]::new()
    try {
        $connectTask = $client.ConnectAsync([System.Net.IPAddress]::Loopback, $port)
        if ($connectTask.Wait(250) -and $client.Connected) {
            $listeners = @(Get-NetTCPConnection -State Listen -ErrorAction Stop |
                Where-Object { $_.LocalPort -eq $port })
            if ($listeners.Count -gt 0 -and @($listeners | Where-Object {
                $_.OwningProcess -ne $process.Id
            }).Count -eq 0) {
                $metadata.Status = 'TcpListening'
                $metadata.TcpListening = $true
                break
            }
        }
    } catch [System.AggregateException] {
        # 아직 포트가 열리지 않은 연결 실패는 다음 시도에서 확인한다.
    } catch [System.Net.Sockets.SocketException] {
        # HTTP 요청은 보내지 않고 TCP 연결만 기다린다.
    } finally {
        $client.Dispose()
    }
    Start-Sleep -Milliseconds 250
}

if (-not $metadata.TcpListening) {
    $metadata.Status = 'TcpWaitTimedOut'
    Write-Warning "45초 안에 TCP 대기를 확인하지 못했습니다. 로그와 metadata를 확인하세요: $metadataPath"
} else {
    Write-Host "TCP 대기 확인 완료. HTTP 응답 검증은 별도로 진행하세요. PID=$($process.Id)"
}
$metadata | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath $metadataPath -Encoding UTF8
if ($metadata.TcpListening) {
    $pointerName = $(if ($CaptureSql) { 'current-capture-api-path.txt' } else { 'current-measure-api-path.txt' })
    $pointerPath = Join-Path (Join-Path $repositoryRoot 'build\benchmark') $pointerName
    $metadataPath | Set-Content -LiteralPath $pointerPath -Encoding UTF8
}
Write-Host "실행 정보: $metadataPath"
Write-Host "종료: & '$PSScriptRoot\stop-benchmark-api.ps1' -MetadataPath '$metadataPath'"
$metadata
