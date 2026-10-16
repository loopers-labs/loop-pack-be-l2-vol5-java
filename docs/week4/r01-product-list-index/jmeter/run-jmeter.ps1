[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string]$ApiMetadataPath,
    [ValidateSet('Smoke', 'Measure')]
    [string]$Mode = 'Smoke',
    [ValidateSet('before', 'after')]
    [string]$Phase = 'before',
    [ValidateSet('latest', 'price', 'brand7')]
    [string[]]$Scenarios = @('latest', 'price', 'brand7'),
    [ValidateSet(1, 10)]
    [int[]]$Users = @(1, 10),
    [ValidateRange(1, 10)]
    [int]$Repetitions = 2,
    [Alias('Warmup')]
    [ValidateRange(1, 120)]
    [int]$WarmupSeconds = 20,
    [Alias('Duration')]
    [ValidateRange(1, 600)]
    [int]$DurationSeconds = 60,
    [Alias('RampUp')]
    [ValidateRange(1, 30)]
    [int]$RampUpSeconds = 5,
    [string]$JavaHome = 'C:\Program Files\Java\jdk-21.0.10',
    [string]$JMeterHome,
    [string]$MySqlContainer = 'docker-mysql-1'
)

$ErrorActionPreference = 'Stop'
$repositoryRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..\..\..'))
if (-not (Test-Path -LiteralPath (Join-Path $repositoryRoot 'gradlew.bat') -PathType Leaf)) {
    throw "저장소 루트를 찾을 수 없습니다: $repositoryRoot"
}
if ($Scenarios.Count -eq 0 -or $Users.Count -eq 0) {
    throw '시나리오와 동시 사용자 목록은 비어 있을 수 없습니다.'
}
$Scenarios = @($Scenarios | ForEach-Object { $_.ToLowerInvariant() })
if (@($Scenarios | Select-Object -Unique).Count -ne $Scenarios.Count -or
    @($Users | Select-Object -Unique).Count -ne $Users.Count) {
    throw '시나리오와 동시 사용자 목록에 중복 값을 넣지 마세요.'
}
$Users = @($Users | Sort-Object)
$Phase = $Phase.ToLowerInvariant()
if ([string]::IsNullOrWhiteSpace($JMeterHome)) {
    $JMeterHome = Join-Path $repositoryRoot 'apache-jmeter-5.6.3'
}
$JMeterHome = [IO.Path]::GetFullPath($JMeterHome)
$JavaHome = [IO.Path]::GetFullPath($JavaHome)
$jmeterBatch = Join-Path $JMeterHome 'bin\jmeter.bat'
$javaExecutable = Join-Path $JavaHome 'bin\java.exe'
$planPath = Join-Path $PSScriptRoot 'product-list.jmx'
$expectedPath = Join-Path $PSScriptRoot 'expected-responses.json'
$propertiesPath = Join-Path $PSScriptRoot 'results.properties'
foreach ($requiredFile in @($jmeterBatch, $javaExecutable, $planPath, $expectedPath, $propertiesPath)) {
    if (-not (Test-Path -LiteralPath $requiredFile -PathType Leaf) -or
        (Get-Item -LiteralPath $requiredFile).Length -eq 0) {
        throw "필수 파일이 없거나 비어 있습니다: $requiredFile"
    }
}
$dockerExecutable = (Get-Command docker -CommandType Application -ErrorAction Stop | Select-Object -First 1).Source
$gitExecutable = (Get-Command git -CommandType Application -ErrorAction Stop | Select-Object -First 1).Source
if (-not (Test-Path -LiteralPath $ApiMetadataPath -PathType Leaf)) {
    throw 'API 실행 정보 파일을 찾을 수 없습니다. 현재 경로 파일을 Get-Content -Raw -Encoding UTF8로 다시 읽으세요.'
}
$resolvedApiMetadata = (Resolve-Path -LiteralPath $ApiMetadataPath -ErrorAction Stop).Path
$apiMetadata = Get-Content -LiteralPath $resolvedApiMetadata -Raw -Encoding UTF8 | ConvertFrom-Json

function Get-UtcTicks {
    param($Value)

    if ($Value -is [DateTimeOffset]) {
        return $Value.UtcTicks
    }
    if ($Value -is [DateTime]) {
        return $Value.ToUniversalTime().Ticks
    }
    return [DateTimeOffset]::Parse([string]$Value, [Globalization.CultureInfo]::InvariantCulture).UtcTicks
}

function Assert-BenchmarkApi {
    if ($apiMetadata.FormatVersion -ne 1 -or
        $apiMetadata.Purpose -ne 'week4-r01-product-list-benchmark-api' -or
        $apiMetadata.Profile -ne 'local' -or $apiMetadata.DatabaseSchema -ne 'week4_r01_index_benchmark' -or
        $apiMetadata.Port -ne 18080 -or $apiMetadata.CaptureSql -isnot [bool] -or $apiMetadata.CaptureSql -or
        $apiMetadata.TcpListening -isnot [bool] -or -not $apiMetadata.TcpListening -or
        $apiMetadata.Status -ne 'TcpListening' -or
        -not [string]::Equals($apiMetadata.RepositoryRoot, $repositoryRoot, [StringComparison]::OrdinalIgnoreCase)) {
        throw 'SQL 로그가 꺼진 현재 저장소의 측정용 API 실행 정보가 필요합니다.'
    }
    $requiredArguments = @(
        '--spring.profiles.active=local'
        '--spring.main.web-application-type=servlet'
        '--server.port=18080'
        '--datasource.mysql-jpa.main.jdbc-url=jdbc:mysql://localhost:3306/week4_r01_index_benchmark?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true'
        '--spring.jpa.hibernate.ddl-auto=none'
        '--spring.jpa.show-sql=false'
        '--benchmark.seed.enabled=false'
        '--scheduler.like-count.enabled=false'
        '--query.product-count-cache.ttl=0s'
        '--management.health.redis.enabled=false'
        '--logging.level.org.hibernate.SQL=OFF'
        '--logging.level.org.hibernate.orm.jdbc.bind=OFF'
    )
    $applicationArguments = @($apiMetadata.ApplicationArguments)
    foreach ($requiredArgument in $requiredArguments) {
        $key = $requiredArgument.Substring(0, $requiredArgument.IndexOf('=')) + '='
        $matchingArguments = @($applicationArguments | Where-Object { $_.StartsWith($key, [StringComparison]::Ordinal) })
        if ($matchingArguments.Count -ne 1 -or $matchingArguments[0] -cne $requiredArgument) {
            throw "측정 API의 필수 CLI 값이 다릅니다: $requiredArgument"
        }
    }
    $targetProcessId = 0
    if (-not [int]::TryParse([string]$apiMetadata.ProcessId, [ref]$targetProcessId) -or $targetProcessId -le 0) {
        throw 'API ProcessId가 올바르지 않습니다.'
    }
    $processInfo = Get-CimInstance Win32_Process -Filter "ProcessId = $targetProcessId" -ErrorAction Stop
    if ($null -eq $processInfo -or $processInfo.Name -ne 'java.exe' -or
        -not [string]::Equals($processInfo.ExecutablePath, $apiMetadata.ExecutablePath, [StringComparison]::OrdinalIgnoreCase) -or
        -not [string]::Equals($processInfo.CommandLine, $apiMetadata.CommandLine, [StringComparison]::Ordinal) -or
        (Get-UtcTicks $processInfo.CreationDate) -ne (Get-UtcTicks $apiMetadata.ProcessCreationUtc) -or
        [string]::IsNullOrWhiteSpace($apiMetadata.JarPath) -or
        $processInfo.CommandLine.IndexOf($apiMetadata.JarPath, [StringComparison]::OrdinalIgnoreCase) -lt 0) {
        throw '기록한 API Java 프로세스·JAR·명령·생성 시각이 현재 실행 상태와 일치하지 않습니다.'
    }
    foreach ($requiredArgument in $requiredArguments) {
        $argumentPattern = '(?:^|\s)' + [regex]::Escape($requiredArgument) + '(?:\s|$)'
        if ($processInfo.CommandLine -notmatch $argumentPattern) {
            throw "실제 API 명령에서 필수 CLI를 확인하지 못했습니다: $requiredArgument"
        }
    }
    $listeners = @(Get-NetTCPConnection -State Listen -ErrorAction Stop | Where-Object { $_.LocalPort -eq 18080 })
    if ($listeners.Count -eq 0 -or @($listeners | Where-Object { $_.OwningProcess -ne $targetProcessId }).Count -gt 0) {
        throw '18080 포트의 실제 소유자가 기록한 API 프로세스와 일치하지 않습니다.'
    }
}

Assert-BenchmarkApi
$sessionName = $Phase + '-' + $Mode.ToLowerInvariant() + '-' + (Get-Date -Format 'yyyyMMdd-HHmmss-fffffff') +
    '-' + [guid]::NewGuid().ToString('N').Substring(0, 8)
$outputDirectory = Join-Path (Join-Path $repositoryRoot 'build\benchmark\jmeter') $sessionName
if (Test-Path -LiteralPath $outputDirectory) {
    throw "결과 경로를 재사용하지 않습니다: $outputDirectory"
}
[void](New-Item -ItemType Directory -Path $outputDirectory)
$manifestPath = Join-Path $outputDirectory 'manifest.json'
$runs = [Collections.Generic.List[object]]::new()
$manifest = [pscustomobject][ordered]@{
    FormatVersion = 1
    Status = 'Started'
    Mode = $Mode
    Phase = $Phase
    StartedAtUtc = [DateTime]::UtcNow.ToString('o')
    FinishedAtUtc = $null
    CodeGitSha = $null
    ApiMetadataPath = $resolvedApiMetadata
    ApiMetadata = $apiMetadata
    ApiJarSha256 = (Get-FileHash -LiteralPath $apiMetadata.JarPath -Algorithm SHA256).Hash
    Plan = @{ Path = $planPath; Sha256 = (Get-FileHash -LiteralPath $planPath -Algorithm SHA256).Hash }
    Expected = @{ Path = $expectedPath; Sha256 = (Get-FileHash -LiteralPath $expectedPath -Algorithm SHA256).Hash }
    Properties = @{ Path = $propertiesPath; Sha256 = (Get-FileHash -LiteralPath $propertiesPath -Algorithm SHA256).Hash }
    Scenarios = $Scenarios
    Users = $(if ($Mode -eq 'Smoke') { @(1) } else { $Users })
    Repetitions = $(if ($Mode -eq 'Smoke') { 1 } else { $Repetitions })
    WarmupSeconds = $(if ($Mode -eq 'Smoke') { 0 } else { $WarmupSeconds })
    DurationSeconds = $(if ($Mode -eq 'Smoke') { 0 } else { $DurationSeconds })
    RampUpSeconds = $(if ($Mode -eq 'Smoke') { 0 } else { $RampUpSeconds })
    JavaHome = $JavaHome
    JMeterHome = $JMeterHome
    Versions = $null
    EnvironmentBefore = $null
    DatabaseBefore = $null
    DatabaseAfter = $null
    Runs = $runs
    Error = $null
    PostflightError = $null
    OutputDirectory = $outputDirectory
    ManifestPath = $manifestPath
}

function Save-Manifest {
    $manifest | ConvertTo-Json -Depth 15 | Set-Content -LiteralPath $manifestPath -Encoding UTF8
}

function Get-DatabaseSnapshot {
    param([string]$Label)

    $sql = @'
SELECT JSON_OBJECT(
    'DatabaseSchema', DATABASE(), 'MySqlVersion', VERSION(),
    'BufferPoolBytes', @@global.innodb_buffer_pool_size,
    'Products', (SELECT COUNT(*) FROM products),
    'Likes', (SELECT COUNT(*) FROM product_likes),
    'ProductIndexes', (SELECT JSON_ARRAYAGG(index_name) FROM
        (SELECT DISTINCT index_name FROM information_schema.statistics
         WHERE table_schema = DATABASE() AND table_name = 'products') names),
    'Settings', JSON_OBJECT(
        'autocommit', @@global.autocommit,
        'innodb_buffer_pool_size', @@global.innodb_buffer_pool_size,
        'innodb_flush_log_at_trx_commit', @@global.innodb_flush_log_at_trx_commit,
        'innodb_redo_log_capacity', @@global.innodb_redo_log_capacity,
        'join_buffer_size', @@global.join_buffer_size,
        'max_heap_table_size', @@global.max_heap_table_size,
        'sort_buffer_size', @@global.sort_buffer_size,
        'sql_mode', @@global.sql_mode,
        'sync_binlog', @@global.sync_binlog,
        'tmp_table_size', @@global.tmp_table_size,
        'transaction_isolation', @@global.transaction_isolation
    )
);
'@
    $arguments = @('exec', '-e', 'MYSQL_PWD=application', $MySqlContainer, 'mysql', '--user=application',
        '--database=week4_r01_index_benchmark', '--default-character-set=utf8mb4', '--batch', '--raw',
        '--skip-column-names', '-e', $sql)
    $stderrPath = Join-Path $outputDirectory "db-$Label-stderr.log"
    $lines = @(& $dockerExecutable @arguments 2> $stderrPath)
    $exitCode = $LASTEXITCODE
    if ($exitCode -ne 0) {
        throw "읽기 전용 DB snapshot 실패 (exit=$exitCode): $stderrPath"
    }
    $snapshot = ($lines -join [Environment]::NewLine) | ConvertFrom-Json
    $snapshot | Add-Member -NotePropertyName CapturedAtUtc -NotePropertyValue ([DateTime]::UtcNow.ToString('o'))
    $snapshot | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (Join-Path $outputDirectory "db-$Label.json") -Encoding UTF8
    return $snapshot
}

function Assert-DatabaseSnapshot {
    param($Snapshot)

    if ($Snapshot.DatabaseSchema -ne 'week4_r01_index_benchmark' -or $Snapshot.MySqlVersion -ne '8.0.46' -or
        $Snapshot.BufferPoolBytes -ne 134217728 -or $Snapshot.Products -ne 1000000 -or $Snapshot.Likes -ne 2597980) {
        throw 'DB·버전·128MiB 버퍼 풀·상품/좋아요 건수가 기준과 다릅니다. 설정을 변경하지 않고 중단합니다.'
    }
    $expectedIndexes = @('PRIMARY', 'idx_products_deleted_brand_created', 'idx_products_deleted_brand_price',
        'idx_products_deleted_like', 'idx_products_deleted_brand_like')
    if ($Phase -eq 'after') {
        $expectedIndexes += @('idx_products_deleted_created', 'idx_products_deleted_price')
    }
    $actualIndexes = @($Snapshot.ProductIndexes | Sort-Object)
    if (($actualIndexes -join '|') -cne (($expectedIndexes | Sort-Object) -join '|')) {
        throw "상품 인덱스가 $Phase 단계와 다릅니다: $($actualIndexes -join ', ')"
    }
    $expectedSettings = [ordered]@{
        autocommit = '1'
        innodb_buffer_pool_size = '134217728'
        innodb_flush_log_at_trx_commit = '1'
        innodb_redo_log_capacity = '104857600'
        join_buffer_size = '262144'
        max_heap_table_size = '16777216'
        sort_buffer_size = '262144'
        sql_mode = 'ONLY_FULL_GROUP_BY,STRICT_TRANS_TABLES,NO_ZERO_IN_DATE,NO_ZERO_DATE,ERROR_FOR_DIVISION_BY_ZERO,NO_ENGINE_SUBSTITUTION'
        sync_binlog = '1'
        tmp_table_size = '16777216'
        transaction_isolation = 'REPEATABLE-READ'
    }
    foreach ($setting in $expectedSettings.Keys) {
        if ([string]$Snapshot.Settings.$setting -cne $expectedSettings[$setting]) {
            throw "MySQL 전역 설정 $setting 이 기준과 다릅니다. 설정을 변경하지 않고 중단합니다."
        }
    }
}

function Read-NativeVersion {
    param([string]$Executable, [string[]]$Arguments)

    $ErrorActionPreference = 'Continue'
    $lines = @($null | & $Executable @Arguments 2>&1)
    $exitCode = $LASTEXITCODE
    if ($exitCode -ne 0) {
        throw "버전 확인 실패: $Executable (exit=$exitCode)"
    }
    return ($lines | ForEach-Object { $_.ToString() }) -join [Environment]::NewLine
}

function Invoke-JMeterStage {
    param([int]$Repetition, [string]$Scenario, [int]$Threads, [string]$Stage, [int]$Seconds, [bool]$Timed)

    Assert-BenchmarkApi
    $runName = 'r{0:D2}-{1}-u{2}' -f $Repetition, $Scenario, $Threads
    $runDirectory = Join-Path $outputDirectory $runName
    if (-not (Test-Path -LiteralPath $runDirectory)) {
        [void](New-Item -ItemType Directory -Path $runDirectory)
    }
    $stageDirectory = Join-Path $runDirectory $Stage
    if (Test-Path -LiteralPath $stageDirectory) {
        throw "실행 결과 경로를 덮어쓰지 않습니다: $stageDirectory"
    }
    [void](New-Item -ItemType Directory -Path $stageDirectory)
    $samplePath = Join-Path $stageDirectory 'samples.jtl'
    $logPath = Join-Path $stageDirectory 'jmeter.log'
    $consolePath = Join-Path $stageDirectory 'console.log'
    $htmlPath = Join-Path $stageDirectory 'html'
    $validation = $(if ($Timed) { 'light' } else { 'full' })
    $scheduler = $(if ($Timed) { 'true' } else { 'false' })
    $loops = $(if ($Timed) { -1 } else { 1 })
    $rampup = $(if ($Timed) { $RampUpSeconds } else { 0 })
    $arguments = @('-n', '-t', $planPath, '-q', $propertiesPath, '-l', $samplePath, '-j', $logPath,
        "-Jscenario=$Scenario", "-Jthreads=$Threads", '-Jhost=localhost', '-Jport=18080',
        '-JconnectTimeout=3000', '-JresponseTimeout=30000', "-JexpectedFile=$expectedPath",
        "-Jvalidation=$validation", "-Jrampup=$rampup", "-Jloops=$loops", "-Jscheduler=$scheduler", "-Jduration=$Seconds")
    if ($Stage -eq 'measurement') {
        $arguments += @('-e', '-o', $htmlPath)
    }
    $record = [pscustomobject][ordered]@{
        RunName = $runName; Repetition = $Repetition; Scenario = $Scenario; Threads = $Threads; Stage = $Stage
        Status = 'Started'; StartedAtUtc = [DateTime]::UtcNow.ToString('o'); FinishedAtUtc = $null
        ElapsedSeconds = $null; ExitCode = $null; Samples = 0; FailedSamples = 0
        Validation = $validation; ScheduledSeconds = $Seconds; RampUpSeconds = $rampup
        Directory = $stageDirectory; JMeter = $jmeterBatch; Arguments = $arguments; Error = $null
    }
    [void]$runs.Add($record)
    $record | ConvertTo-Json -Depth 6 |
        Set-Content -LiteralPath (Join-Path $stageDirectory 'arguments.json') -Encoding UTF8
    Save-Manifest
    $timer = [Diagnostics.Stopwatch]::StartNew()
    Write-Host "$Phase / $Mode / $runName / $Stage (duration=$Seconds, validation=$validation)"
    try {
        # 배치의 오류 pause에 입력을 기다리지 않도록 stdin을 EOF로 끝낸다.
        $previousErrorAction = $ErrorActionPreference
        try {
            $ErrorActionPreference = 'Continue'
            $null | & $jmeterBatch @arguments 2>&1 | Tee-Object -FilePath $consolePath -ErrorAction Stop | Out-Host
            $record.ExitCode = $LASTEXITCODE
        } finally {
            $ErrorActionPreference = $previousErrorAction
        }
        if ($record.ExitCode -ne 0) {
            throw "JMeter CLI 종료 코드가 0이 아닙니다: $($record.ExitCode)"
        }
        if (-not (Test-Path -LiteralPath $samplePath -PathType Leaf)) {
            throw 'JTL 결과 파일이 없습니다.'
        }
        Import-Csv -LiteralPath $samplePath | ForEach-Object {
            $record.Samples++
            if (-not ($_.PSObject.Properties.Name -contains 'success')) {
                throw 'JTL에 success 열이 없습니다. results.properties와 CSV 형식을 확인하세요.'
            }
            if ($_.success -ne 'true') {
                $record.FailedSamples++
            }
        }
        if ($record.Samples -eq 0 -or $record.FailedSamples -gt 0) {
            throw "JTL 검증 실패: samples=$($record.Samples), failed=$($record.FailedSamples). 원본을 보존합니다."
        }
        if (-not $Timed -and $record.Samples -ne 1) {
            throw "Smoke는 1명·1건이어야 합니다: samples=$($record.Samples)"
        }
        if ($Stage -eq 'measurement' -and -not (Test-Path -LiteralPath (Join-Path $htmlPath 'index.html') -PathType Leaf)) {
            throw 'JMeter HTML 보고서가 생성되지 않았습니다.'
        }
        $record.Status = 'Finished'
    } catch {
        $record.Status = 'Failed'
        $record.Error = $_.Exception.Message
        throw
    } finally {
        $record.FinishedAtUtc = [DateTime]::UtcNow.ToString('o')
        $record.ElapsedSeconds = [Math]::Round($timer.Elapsed.TotalSeconds, 3)
        $record | ConvertTo-Json -Depth 6 |
            Set-Content -LiteralPath (Join-Path $stageDirectory 'arguments.json') -Encoding UTF8
        Save-Manifest
    }
}

$environmentNames = @('JAVA_HOME', 'PATH', 'JMETER_HOME', 'JMETER_BIN', 'JM_LAUNCH', 'JM_START')
$previousEnvironment = [ordered]@{}
foreach ($name in $environmentNames) {
    $previousEnvironment[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
}
$manifest.EnvironmentBefore = [ordered]@{
    JAVA_HOME = $previousEnvironment['JAVA_HOME']; JMETER_HOME = $previousEnvironment['JMETER_HOME']
    JMETER_BIN = $previousEnvironment['JMETER_BIN']; JM_LAUNCH = $previousEnvironment['JM_LAUNCH']
    JM_START = $previousEnvironment['JM_START']; HEAP = $env:HEAP; JVM_ARGS = $env:JVM_ARGS
    JMETER_COMPLETE_ARGS = $env:JMETER_COMPLETE_ARGS
}
Save-Manifest
$failure = $null
try {
    # 배포 bat는 JAVA_HOME 대신 PATH/JM_LAUNCH로 Java를 찾는다. 변경은 이 프로세스 안에서만 유지한다.
    [Environment]::SetEnvironmentVariable('JAVA_HOME', $JavaHome, 'Process')
    [Environment]::SetEnvironmentVariable('PATH', (Join-Path $JavaHome 'bin') + ';' + $previousEnvironment['PATH'], 'Process')
    [Environment]::SetEnvironmentVariable('JMETER_HOME', $JMeterHome, 'Process')
    [Environment]::SetEnvironmentVariable('JMETER_BIN', (Join-Path $JMeterHome 'bin') + '\', 'Process')
    [Environment]::SetEnvironmentVariable('JM_LAUNCH', $javaExecutable, 'Process')
    [Environment]::SetEnvironmentVariable('JM_START', $null, 'Process')
    $manifest.CodeGitSha = Read-NativeVersion $gitExecutable @('-C', $repositoryRoot, 'rev-parse', 'HEAD')
    $manifest.Versions = [ordered]@{
        Java = (Read-NativeVersion $javaExecutable @('-version'))
        JMeter = (Read-NativeVersion $jmeterBatch @('-v', '-j', (Join-Path $outputDirectory 'version-jmeter.log')))
        Docker = (Read-NativeVersion $dockerExecutable @('--version'))
        PowerShell = $PSVersionTable.PSVersion.ToString()
    }
    if ($manifest.Versions.JMeter -notmatch '\b5\.6\.3\b') {
        throw 'Apache JMeter 5.6.3 실행을 확인하지 못했습니다.'
    }
    $manifest.DatabaseBefore = Get-DatabaseSnapshot 'before'
    Save-Manifest
    Assert-DatabaseSnapshot $manifest.DatabaseBefore
    if ($Mode -eq 'Smoke') {
        foreach ($scenario in $Scenarios) {
            Invoke-JMeterStage 1 $scenario 1 'measurement' 0 $false
        }
    } else {
        for ($repetition = 1; $repetition -le $Repetitions; $repetition++) {
            for ($offset = 0; $offset -lt $Scenarios.Count; $offset++) {
                $scenario = $Scenarios[($offset + $repetition - 1) % $Scenarios.Count]
                foreach ($threadCount in $Users) {
                    Invoke-JMeterStage $repetition $scenario $threadCount 'warmup' $WarmupSeconds $true
                    Invoke-JMeterStage $repetition $scenario $threadCount 'measurement' $DurationSeconds $true
                }
            }
        }
    }
    Assert-BenchmarkApi
} catch {
    $failure = $_
    $manifest.Error = $_.Exception.Message
} finally {
    try {
        $manifest.DatabaseAfter = Get-DatabaseSnapshot 'after'
        Assert-DatabaseSnapshot $manifest.DatabaseAfter
    } catch {
        $manifest.PostflightError = $_.Exception.Message
        if ($null -eq $failure) {
            $failure = $_
            $manifest.Error = $_.Exception.Message
        }
    } finally {
        foreach ($name in $environmentNames) {
            [Environment]::SetEnvironmentVariable($name, $previousEnvironment[$name], 'Process')
        }
        $manifest.Status = $(if ($null -eq $failure) { 'Finished' } else { 'Failed' })
        $manifest.FinishedAtUtc = [DateTime]::UtcNow.ToString('o')
        Save-Manifest
    }
}
if ($null -ne $failure) {
    Write-Host "실패 원본과 manifest를 보존했습니다: $manifestPath"
    throw $failure
}
Write-Host "완료: $manifestPath"
if ($Mode -eq 'Smoke') {
    Write-Host 'Smoke의 1건 HTML은 도구·응답 점검용입니다. 성능 측정 결과로 해석하지 마세요.'
}
$manifest
