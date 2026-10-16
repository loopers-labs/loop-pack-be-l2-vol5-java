[CmdletBinding()]
param(
    [ValidateSet('Preflight','Smoke','Measure')][string]$Mode = 'Preflight',
    [Parameter(Mandatory = $true)][string]$JarPath,
    [string]$JavaHome = 'C:\Program Files\Java\jdk-21.0.10',
    [string]$JMeterHome,
    [string]$MySqlContainer = 'docker-mysql-1'
)

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'benchmark-common.ps1')
$repositoryRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..\..\..'))
if (-not (Test-Path -LiteralPath (Join-Path $repositoryRoot 'gradlew.bat') -PathType Leaf)) {
    throw 'Cannot locate this repository root.'
}
if (-not [IO.Path]::IsPathRooted($JarPath)) { $JarPath = Join-Path $repositoryRoot $JarPath }
$JarPath = [IO.Path]::GetFullPath($JarPath)
$javaExecutable = [IO.Path]::GetFullPath((Join-Path $JavaHome 'bin\java.exe'))
if ([string]::IsNullOrWhiteSpace($JMeterHome)) { $JMeterHome = Join-Path $repositoryRoot 'apache-jmeter-5.6.3' }
$JMeterHome = [IO.Path]::GetFullPath($JMeterHome)
$jmeterJar = Join-Path $JMeterHome 'bin\ApacheJMeter.jar'
$planPath = Join-Path $PSScriptRoot 'product-detail.jmx'
$idsPath = Join-Path $PSScriptRoot 'product-ids.csv'
$expectedPath = Join-Path $PSScriptRoot 'expected-responses.json'
$propertiesPath = Join-Path $PSScriptRoot 'results.properties'
foreach ($pathValue in @($JarPath, $javaExecutable, $jmeterJar, $planPath, $idsPath, $expectedPath, $propertiesPath)) {
    if (-not (Test-Path -LiteralPath $pathValue -PathType Leaf) -or (Get-Item -LiteralPath $pathValue).Length -eq 0) {
        throw "Required input is missing or empty: $pathValue"
    }
}
Add-Type -AssemblyName System.IO.Compression.FileSystem
$appArchive = [IO.Compression.ZipFile]::OpenRead($JarPath)
try {
    if ($null -eq $appArchive.GetEntry('BOOT-INF/classes/com/loopers/CommerceApiApplication.class') -or
        @($appArchive.Entries | Where-Object { $_.FullName -like 'BOOT-INF/lib/caffeine-*.jar' }).Count -ne 1) {
        throw 'Select the newly built commerce-api Spring Boot JAR containing Caffeine.'
    }
} finally { $appArchive.Dispose() }
$jmeterArchive = [IO.Compression.ZipFile]::OpenRead($jmeterJar)
try {
    $reader = [IO.StreamReader]::new($jmeterArchive.GetEntry('META-INF/MANIFEST.MF').Open(), [Text.Encoding]::UTF8)
    try { $jmeterManifest = $reader.ReadToEnd() } finally { $reader.Dispose() }
} finally { $jmeterArchive.Dispose() }
if ($jmeterManifest -notmatch '(?m)^Implementation-Version: 5\.6\.3\s*$') {
    throw 'Apache JMeter 5.6.3 is required.'
}
$ids = @(Import-Csv -LiteralPath $idsPath -Encoding UTF8 | ForEach-Object { [long]$_.productId })
$fixture = Get-Content -LiteralPath $expectedPath -Raw -Encoding UTF8 | ConvertFrom-Json
if ($ids.Count -ne 100 -or @($ids | Select-Object -Unique).Count -ne 100 -or
    ($ids -join ',') -cne (@($fixture.seed.expectedIds) -join ',') -or
    @($fixture.responses.PSObject.Properties).Count -ne 100) {
    throw 'The fixed CSV and independent oracle must contain the same 100 distinct IDs.'
}
Assert-R02PortsFree
$sessionName = $Mode.ToLowerInvariant() + '-' + (Get-Date -Format 'yyyyMMdd-HHmmss-fffffff') + '-' +
    [guid]::NewGuid().ToString('N').Substring(0, 8)
$outputDirectory = Join-Path (Join-Path $repositoryRoot 'build\benchmark\r02') $sessionName
[void](New-Item -ItemType Directory -Path $outputDirectory)
$manifestPath = Join-Path $outputDirectory 'manifest.json'
$inputRecords = @(@($planPath, $idsPath, $expectedPath, $propertiesPath, $PSCommandPath,
    (Join-Path $PSScriptRoot 'benchmark-common.ps1')) | ForEach-Object {
        [pscustomobject]@{ Path = $_; Sha256 = (Get-FileHash -LiteralPath $_ -Algorithm SHA256).Hash }
    })
$javaVersionLines = @()
$previousErrorAction = $ErrorActionPreference
try {
    $ErrorActionPreference = 'Continue'
    $javaVersionLines = @(& $javaExecutable -version 2>&1 | ForEach-Object { $_.ToString() })
    $javaVersionExitCode = $LASTEXITCODE
} finally { $ErrorActionPreference = $previousErrorAction }
if ($javaVersionExitCode -ne 0 -or ($javaVersionLines -join ' ') -notmatch 'version "21\.') {
    throw 'The selected Java executable must be JDK 21.'
}
$git = (Get-Command git -CommandType Application -ErrorAction Stop | Select-Object -First 1).Source
$codeSha = @(& $git -C $repositoryRoot rev-parse HEAD) -join ''
if ($LASTEXITCODE -ne 0) { throw 'Cannot record the source Git SHA.' }
$runs = [Collections.Generic.List[object]]::new()
$apis = [Collections.Generic.List[object]]::new()
$protectedBefore = @(Get-R02ProtectedListeners)
Write-R02Json $protectedBefore (Join-Path $outputDirectory 'protected-listeners-before.json')
$manifest = [pscustomobject][ordered]@{
    FormatVersion = 1; Purpose = 'week4-r02-product-detail-cache-benchmark'; Mode = $Mode; Status = 'Started'
    StartedAtUtc = [DateTime]::UtcNow.ToString('o'); FinishedAtUtc = $null
    RepositoryRoot = $repositoryRoot; CodeGitSha = $codeSha; JarPath = $JarPath
    JarSha256 = (Get-FileHash -LiteralPath $JarPath -Algorithm SHA256).Hash
    JavaExecutable = $javaExecutable; JavaVersion = ($javaVersionLines -join [Environment]::NewLine)
    PowerShellVersion = $PSVersionTable.PSVersion.ToString(); JMeterHome = $JMeterHome; JMeterVersion = '5.6.3'
    JMeterJarSha256 = (Get-FileHash -LiteralPath $jmeterJar -Algorithm SHA256).Hash
    Inputs = $inputRecords; ApiPort = 18081; ManagementPort = 18082; ProtectedPorts = @(18080, 8081)
    MeasurementOrder = @('r1-OFF-u1','r1-OFF-u10','r1-ON-u1','r1-ON-u10',
        'r2-ON-u1','r2-ON-u10','r2-OFF-u1','r2-OFF-u10')
    WarmupSeconds = 20; MeasurementSeconds = 60; RampupSeconds = 5; Repetitions = 2; Users = @(1,10)
    ConnectTimeoutMs = 3000; ResponseTimeoutMs = 30000; CacheMaximumSize = 1000; CacheTtl = '30s'
    WorkloadIds = $ids; SmokeRequestsPerMode = 100; Processes = $apis; Runs = $runs
    DatabaseBefore = $null; DatabaseAfter = $null
    Error = $null; CleanupErrors = @(); OutputDirectory = $outputDirectory; ManifestPath = $manifestPath
    InheritedJavaEnvironment = [ordered]@{
        JAVA_TOOL_OPTIONS = $env:JAVA_TOOL_OPTIONS; JDK_JAVA_OPTIONS = $env:JDK_JAVA_OPTIONS; _JAVA_OPTIONS = $env:_JAVA_OPTIONS
    }
}
function Save-R02Manifest { Write-R02Json $manifest $manifestPath }

function Assert-R02InputHashes {
    if ((Get-FileHash -LiteralPath $JarPath -Algorithm SHA256).Hash -cne $manifest.JarSha256) {
        throw 'The application JAR changed during this benchmark.'
    }
    foreach ($inputRecord in $inputRecords) {
        if ((Get-FileHash -LiteralPath $inputRecord.Path -Algorithm SHA256).Hash -cne $inputRecord.Sha256) {
            throw "Benchmark input changed: $($inputRecord.Path)"
        }
    }
}

function Read-R02Samples {
    param([string]$Path)
    $count = 0L; $failed = 0L; $sum = 0.0; $first = [long]::MaxValue; $last = 0L
    $elapsedValues = [Collections.Generic.List[double]]::new()
    $seenIds = [Collections.Generic.HashSet[long]]::new()
    Import-Csv -LiteralPath $Path -Encoding UTF8 | ForEach-Object {
        if ($_.PSObject.Properties.Name -notcontains 'success' -or
            $_.PSObject.Properties.Name -notcontains 'productId') { throw 'JTL columns are missing.' }
        $count++
        if ($_.success -cne 'true') { $failed++ }
        $elapsed = [double]::Parse($_.elapsed, [Globalization.CultureInfo]::InvariantCulture)
        $start = [long]::Parse($_.timeStamp, [Globalization.CultureInfo]::InvariantCulture)
        $end = $start + [long][Math]::Ceiling($elapsed)
        if ($start -lt $first) { $first = $start }
        if ($end -gt $last) { $last = $end }
        $sum += $elapsed; $elapsedValues.Add($elapsed)
        [void]$seenIds.Add([long]$_.productId)
    }
    if ($count -eq 0) { throw 'JMeter produced zero samples.' }
    $elapsedValues.Sort()
    $p50 = $elapsedValues[[Math]::Max(0, [int][Math]::Ceiling($count * 0.50) - 1)]
    $p95 = $elapsedValues[[Math]::Max(0, [int][Math]::Ceiling($count * 0.95) - 1)]
    $p99 = $elapsedValues[[Math]::Max(0, [int][Math]::Ceiling($count * 0.99) - 1)]
    $spanSeconds = ($last - $first) / 1000.0
    return [pscustomobject][ordered]@{
        Samples = $count; FailedSamples = $failed; ErrorRate = $failed / [double]$count
        AverageMs = $sum / $count; P50Ms = $p50; P95Ms = $p95; P99Ms = $p99
        PercentileMethod = 'nearest-rank from raw elapsed milliseconds'
        ThroughputPerSecond = $(if ($spanSeconds -gt 0) { $count / $spanSeconds } else { $null })
        SampleSpanSeconds = $spanSeconds; UniqueIds = $seenIds.Count; ObservedIds = @($seenIds | Sort-Object)
    }
}

$jmeterJvm = @(
    '--add-opens=java.desktop/sun.awt=ALL-UNNAMED', '--add-opens=java.desktop/sun.swing=ALL-UNNAMED',
    '--add-opens=java.desktop/javax.swing.text.html=ALL-UNNAMED', '--add-opens=java.desktop/java.awt=ALL-UNNAMED',
    '--add-opens=java.desktop/java.awt.font=ALL-UNNAMED', '--add-opens=java.base/java.lang=ALL-UNNAMED',
    '--add-opens=java.base/java.lang.invoke=ALL-UNNAMED', '--add-opens=java.base/java.lang.reflect=ALL-UNNAMED',
    '--add-opens=java.base/java.util=ALL-UNNAMED', '--add-opens=java.base/java.text=ALL-UNNAMED',
    '--add-opens=java.desktop/sun.awt.shell=ALL-UNNAMED',
    '-XX:+HeapDumpOnOutOfMemoryError', ("-XX:HeapDumpPath=$outputDirectory"),
    '-Xms1g', '-Xmx1g', '-XX:MaxMetaspaceSize=256m',
    '-XX:+UseG1GC', '-XX:MaxGCPauseMillis=100', '-XX:G1ReservePercent=20',
    '-Djava.security.egd=file:/dev/urandom', '-Duser.language=en', '-Duser.region=EN',
    '-Djava.awt.headless=true', '-Dfile.encoding=UTF-8'
)

function Invoke-R02Stage {
    param($Api, [int]$Repetition, [string]$CacheMode, [int]$Users,
        [ValidateSet('smoke','warmup','measurement')][string]$Stage, [int]$Seconds, [string]$Directory)
    Assert-R02InputHashes
    Assert-R02ApiListeners $Api.Metadata
    if ($Api.Metadata.CaptureSql) { throw 'SQL capture must be OFF during JMeter.' }
    if (Test-Path -LiteralPath $Directory) { throw "Stage output already exists: $Directory" }
    [void](New-Item -ItemType Directory -Path $Directory)
    $samplePath = Join-Path $Directory 'samples.jtl'
    $logPath = Join-Path $Directory 'jmeter.log'
    $htmlPath = Join-Path $Directory 'html'
    $timed = $Stage -ne 'smoke'
    $validation = $(if ($timed) { 'light' } else { 'full' })
    $loops = $(if ($timed) { -1 } else { 100 })
    $scheduler = $(if ($timed) { 'true' } else { 'false' })
    $rampup = $(if ($timed) { 5 } else { 0 })
    $jmeterArguments = @('-n', '-t', $planPath, '-q', $propertiesPath, '-l', $samplePath, '-j', $logPath,
        '-Jhost=localhost', '-Jport=18081', ("-Jthreads=$Users"), ("-Jrampup=$rampup"),
        ("-Jloops=$loops"), ("-Jscheduler=$scheduler"), ("-Jduration=$Seconds"),
        '-JconnectTimeout=3000', '-JresponseTimeout=30000', ("-Jvalidation=$validation"),
        ("-JidsFile=$idsPath"), ("-JexpectedFile=$expectedPath"))
    if ($Stage -eq 'measurement') { $jmeterArguments += @('-e', '-o', $htmlPath) }
    $javaArguments = @($jmeterJvm) + @('-jar', $jmeterJar) + @($jmeterArguments)
    $record = [pscustomobject][ordered]@{
        Repetition = $Repetition; CacheMode = $CacheMode; Users = $Users; Stage = $Stage
        Status = 'Started'; StartedAtUtc = [DateTime]::UtcNow.ToString('o'); FinishedAtUtc = $null
        Seconds = $Seconds; RampupSeconds = $rampup; Loops = $loops; Validation = $validation
        Directory = $Directory; JMeterArguments = $jmeterArguments; JvmArguments = $jmeterJvm
        Process = $null; Samples = $null; HtmlStatistics = $null
        MetricsBefore = $null; MetricsAfter = $null; MetricDelta = $null
        ElapsedSeconds = $null; ExitCode = $null; Error = $null; CleanupError = $null
    }
    [void]$runs.Add($record); Save-R02Manifest
    $owned = $null
    $cleanupFailure = $null
    $timer = [Diagnostics.Stopwatch]::StartNew()
    try {
        $record.MetricsBefore = Read-R02Metrics $Api.Metadata (Join-Path $Directory 'metrics-before.prom')
        $owned = Start-R02OwnedJava $repositoryRoot $javaExecutable $jmeterJar $javaArguments 'JMeter' $Directory $CacheMode
        $record.Process = $owned.Metadata; Save-R02Manifest
        $waitLimit = $Seconds + 150
        while (-not $owned.Process.WaitForExit(500)) {
            if ($timer.Elapsed.TotalSeconds -gt $waitLimit) { throw "JMeter exceeded its stage deadline: $Directory" }
            if ($Api.Process.HasExited) { throw 'R02 API exited during JMeter.' }
        }
        $owned.Process.WaitForExit()
        $record.ExitCode = $owned.Process.ExitCode
        $owned.Metadata.Status = 'Exited'; $owned.Metadata.ExitCode = $record.ExitCode
        $owned.Metadata.StoppedAtUtc = [DateTime]::UtcNow.ToString('o')
        Write-R02Json $owned.Metadata $owned.Metadata.MetadataPath
        $record.MetricsAfter = Read-R02Metrics $Api.Metadata (Join-Path $Directory 'metrics-after.prom')
        if ($record.ExitCode -ne 0) { throw "JMeter exited with code $($record.ExitCode)." }
        if (-not (Test-Path -LiteralPath $samplePath -PathType Leaf)) { throw 'No JTL was produced.' }
        $record.Samples = Read-R02Samples $samplePath
        $record.MetricDelta = Get-R02MetricDelta $record.MetricsBefore $record.MetricsAfter $record.Samples.Samples
        Write-R02Json $record.MetricDelta (Join-Path $Directory 'metrics-delta.json')
        if ($record.Samples.FailedSamples -gt 0) { throw "JMeter assertion/HTTP failures: $($record.Samples.FailedSamples)" }
        if ($Stage -eq 'smoke' -and ($record.Samples.Samples -ne 100 -or $record.Samples.UniqueIds -ne 100)) {
            throw 'Smoke must verify each of the 100 fixed IDs exactly once.'
        }
        if ($timed -and $record.Samples.Samples -le 1) { throw 'Timed run produced too few samples.' }
        if ($Stage -eq 'measurement' -and -not (Test-Path -LiteralPath (Join-Path $htmlPath 'index.html') -PathType Leaf)) {
            throw 'Main-run HTML report is missing.'
        }
        if ($Stage -eq 'measurement') {
            $statistics = Get-Content -LiteralPath (Join-Path $htmlPath 'statistics.json') -Raw -Encoding UTF8 | ConvertFrom-Json
            $labelStatistics = $statistics | Select-Object -ExpandProperty 'product-detail'
            if ($labelStatistics.sampleCount -ne $record.Samples.Samples -or
                $labelStatistics.errorCount -ne $record.Samples.FailedSamples -or
                [Math]::Abs($labelStatistics.meanResTime - $record.Samples.AverageMs) -gt 0.000001 -or
                [Math]::Abs($labelStatistics.throughput - $record.Samples.ThroughputPerSecond) -gt
                    [Math]::Max(0.000001, $record.Samples.ThroughputPerSecond * 0.000001)) {
                throw 'HTML statistics sample count/errors/average/throughput differ from the raw JTL.'
            }
            $record.HtmlStatistics = $labelStatistics
        }
        $record.Status = 'Finished'
    } catch {
        $record.Status = 'Failed'; $record.Error = $_.Exception.Message
        throw
    } finally {
        if ($record.Status -eq 'Started') {
            $record.Status = 'Interrupted'
            $record.Error = 'Stage interrupted before normal completion.'
        }
        if ($null -ne $owned -and -not $owned.Process.HasExited) {
            try { Stop-R02OwnedJava $owned.Metadata }
            catch {
                $record.CleanupError = $_.Exception.Message
                $record.Status = 'Failed'
                $cleanupFailure = $_
            }
        }
        $record.FinishedAtUtc = [DateTime]::UtcNow.ToString('o')
        $record.ElapsedSeconds = [Math]::Round($timer.Elapsed.TotalSeconds, 3)
        Write-R02Json $record (Join-Path $Directory 'stage.json')
        Save-R02Manifest
        if ($null -ne $cleanupFailure) { throw $cleanupFailure }
    }
}

$failure = $null
$completed = $false
$currentApi = $null
Save-R02Manifest
try {
    $manifest.DatabaseBefore = Read-R02Database $MySqlContainer $outputDirectory 'before'
    Save-R02Manifest
    if ($Mode -ne 'Preflight') {
        $order = $(if ($Mode -eq 'Smoke') {
            @([pscustomobject]@{ Repetition = 0; CacheMode = 'OFF' }, [pscustomobject]@{ Repetition = 0; CacheMode = 'ON' })
        } else {
            @([pscustomobject]@{ Repetition = 1; CacheMode = 'OFF' }, [pscustomobject]@{ Repetition = 1; CacheMode = 'ON' },
              [pscustomobject]@{ Repetition = 2; CacheMode = 'ON' }, [pscustomobject]@{ Repetition = 2; CacheMode = 'OFF' })
        })
        foreach ($entry in $order) {
            Assert-R02InputHashes
            $modeDirectory = Join-Path $outputDirectory ('r{0}-{1}' -f $entry.Repetition, $entry.CacheMode.ToLowerInvariant())
            $currentApi = Start-R02Api $repositoryRoot $javaExecutable $JarPath $entry.CacheMode (Join-Path $modeDirectory 'api')
            [void]$apis.Add($currentApi.Metadata); Save-R02Manifest
            try {
                if ($Mode -eq 'Smoke') {
                    Invoke-R02Stage $currentApi $entry.Repetition $entry.CacheMode 1 'smoke' 0 (Join-Path $modeDirectory 'smoke')
                } else {
                    foreach ($usersValue in @(1,10)) {
                        $userDirectory = Join-Path $modeDirectory ("u$usersValue")
                        Invoke-R02Stage $currentApi $entry.Repetition $entry.CacheMode $usersValue 'warmup' 20 (Join-Path $userDirectory 'warmup')
                        Invoke-R02Stage $currentApi $entry.Repetition $entry.CacheMode $usersValue 'measurement' 60 (Join-Path $userDirectory 'measurement')
                    }
                }
            } finally {
                Stop-R02OwnedJava $currentApi.Metadata
                $currentApi = $null
                Save-R02Manifest
            }
        }
        if ($Mode -eq 'Measure' -and @($runs | Where-Object { $_.Stage -eq 'measurement' -and $_.Status -eq 'Finished' }).Count -ne 8) {
            throw 'Measure did not finish all eight main runs.'
        }
    }
    Assert-R02InputHashes
    $completed = $true
} catch {
    $failure = $_
    $manifest.Error = $_.Exception.Message
} finally {
    if (-not $completed -and $null -eq $failure) {
        $failure = [InvalidOperationException]::new('Session interrupted before normal completion.')
        $manifest.Error = $failure.Message
    }
    if ($null -ne $currentApi) {
        try { Stop-R02OwnedJava $currentApi.Metadata }
        catch {
            $manifest.CleanupErrors += $_.Exception.Message
            if ($null -eq $failure) { $failure = $_; $manifest.Error = $_.Exception.Message }
        }
    }
    try {
        $manifest.DatabaseAfter = Read-R02Database $MySqlContainer $outputDirectory 'after'
        $beforeSettings = $manifest.DatabaseBefore.Settings | ConvertTo-Json -Compress
        $afterSettings = $manifest.DatabaseAfter.Settings | ConvertTo-Json -Compress
        if ($beforeSettings -cne $afterSettings) { throw 'DB settings changed across this session.' }
        $protectedAfter = @(Get-R02ProtectedListeners)
        Write-R02Json $protectedAfter (Join-Path $outputDirectory 'protected-listeners-after.json')
        if (($protectedBefore | ConvertTo-Json -Compress) -cne ($protectedAfter | ConvertTo-Json -Compress)) {
            throw 'Protected listeners on 18080/8081 changed during the session; no protected process was stopped.'
        }
        Assert-R02PortsFree
    } catch {
        $manifest.CleanupErrors += $_.Exception.Message
        if ($null -eq $failure) { $failure = $_; $manifest.Error = $_.Exception.Message }
    }
    $manifest.Status = $(if ($null -eq $failure) { 'Finished' } else { 'Failed' })
    $manifest.FinishedAtUtc = [DateTime]::UtcNow.ToString('o')
    Write-R02Json @($runs | Where-Object { $_.Stage -eq 'measurement' }) (Join-Path $outputDirectory 'measurement-summary.json')
    Save-R02Manifest
}
Write-Host "R02 evidence: $manifestPath"
if ($null -ne $failure) { throw $failure }
$manifest
