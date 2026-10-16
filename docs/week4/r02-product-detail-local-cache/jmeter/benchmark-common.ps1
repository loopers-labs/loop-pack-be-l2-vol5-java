Set-StrictMode -Version 2

function Write-R02Json {
    param([Parameter(Mandatory = $true)]$Value, [Parameter(Mandatory = $true)][string]$Path)
    ConvertTo-Json -InputObject $Value -Depth 20 | Set-Content -LiteralPath $Path -Encoding UTF8
}

function ConvertTo-R02NativeArgument {
    param([string]$Value)
    if ($Value.Length -gt 0 -and $Value -notmatch '[\s"]') { return $Value }
    $escaped = [regex]::Replace($Value, '(\\*)"', '$1$1\"')
    $escaped = [regex]::Replace($escaped, '(\\+)$', '$1$1')
    return '"' + $escaped + '"'
}

function Get-R02UtcTicks {
    param($Value)
    if ($Value -is [DateTime]) { return $Value.ToUniversalTime().Ticks }
    if ($Value -is [DateTimeOffset]) { return $Value.UtcTicks }
    return [DateTimeOffset]::Parse([string]$Value, [Globalization.CultureInfo]::InvariantCulture).UtcTicks
}

function Assert-R02PortsFree {
    $listeners = @(Get-NetTCPConnection -State Listen -ErrorAction Stop |
        Where-Object { $_.LocalPort -in @(18081, 18082) })
    if ($listeners.Count -gt 0) {
        throw ('R02 ports 18081/18082 are already in use. Existing processes were preserved: ' +
            (($listeners | ForEach-Object { '{0}:{1}' -f $_.LocalPort, $_.OwningProcess }) -join ', '))
    }
}

function Get-R02ProtectedListeners {
    return @(Get-NetTCPConnection -State Listen -ErrorAction Stop |
        Where-Object { $_.LocalPort -in @(18080, 8081) } |
        Sort-Object LocalPort, LocalAddress, OwningProcess |
        ForEach-Object {
            [pscustomobject][ordered]@{
                LocalAddress = $_.LocalAddress
                LocalPort = $_.LocalPort
                ProcessId = $_.OwningProcess
            }
        })
}

function Assert-R02Database {
    param($Snapshot)
    if ($Snapshot.DatabaseSchema -cne 'week4_r01_index_benchmark' -or $Snapshot.MySqlVersion -cne '8.0.46' -or
        $Snapshot.Products -ne 1000000 -or $Snapshot.ActiveProducts -ne 950400 -or
        $Snapshot.Likes -ne 2597980 -or $Snapshot.StoredLikes -ne 2597980 -or
        $Snapshot.PriceSum -ne 50950000000 -or $Snapshot.Brands -ne 1000 -or $Snapshot.Users -ne 1000 -or
        $Snapshot.FixtureUsers -ne 2 -or $Snapshot.Wallets -ne 1000 -or $Snapshot.FixtureWallets -ne 2 -or
        $Snapshot.WalletBalanceSum -ne 0 -or $Snapshot.WalletsWithNonzeroBalance -ne 0) {
        throw 'DB version/schema/fixture differs from the R02 baseline. No settings were changed.'
    }
    $expectedIndexes = [ordered]@{
        PRIMARY = 'id A'
        idx_products_deleted_brand_created = 'deleted A,brand_id A,created_at D,id D'
        idx_products_deleted_brand_price = 'deleted A,brand_id A,price A,id D'
        idx_products_deleted_like = 'deleted A,like_count D,id D'
        idx_products_deleted_brand_like = 'deleted A,brand_id A,like_count D,id D'
        idx_products_deleted_created = 'deleted A,created_at D,id D'
        idx_products_deleted_price = 'deleted A,price A,id D'
    }
    if (@($Snapshot.ProductIndexes.PSObject.Properties).Count -ne $expectedIndexes.Count) {
        throw 'Expected PRIMARY and exactly six product secondary indexes.'
    }
    foreach ($name in $expectedIndexes.Keys) {
        if ([string]$Snapshot.ProductIndexes.$name -cne $expectedIndexes[$name]) {
            throw "Product index differs: $name"
        }
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
    foreach ($name in $expectedSettings.Keys) {
        if ([string]$Snapshot.Settings.$name -cne $expectedSettings[$name]) {
            throw "MySQL setting differs: $name. No setting was changed."
        }
    }
}

function Read-R02Database {
    param([string]$Container, [string]$Directory, [string]$Label)
    $docker = (Get-Command docker -CommandType Application -ErrorAction Stop | Select-Object -First 1).Source
    $sql = @'
SELECT JSON_OBJECT(
 'DatabaseSchema', DATABASE(), 'MySqlVersion', VERSION(),
 'Products', (SELECT COUNT(*) FROM products),
 'ActiveProducts', (SELECT COUNT(*) FROM products WHERE deleted = false),
 'Likes', (SELECT COUNT(*) FROM product_likes),
 'StoredLikes', (SELECT SUM(like_count) FROM products),
 'PriceSum', (SELECT SUM(price) FROM products),
 'Brands', (SELECT COUNT(*) FROM brands), 'Users', (SELECT COUNT(*) FROM users),
 'FixtureUsers', (SELECT COUNT(*) FROM users WHERE id IN (1, 2)),
 'Wallets', (SELECT COUNT(*) FROM wallets),
 'FixtureWallets', (SELECT COUNT(*) FROM wallets WHERE user_id IN (1, 2)),
 'WalletBalanceSum', (SELECT SUM(balance) FROM wallets),
 'WalletsWithNonzeroBalance', (SELECT COUNT(*) FROM wallets WHERE balance <> 0),
 'ProductIndexes', (SELECT JSON_OBJECTAGG(index_name, definition) FROM (
   SELECT index_name, GROUP_CONCAT(CONCAT(column_name, ' ', collation)
     ORDER BY seq_in_index SEPARATOR ',') AS definition
   FROM information_schema.statistics
   WHERE table_schema = DATABASE() AND table_name = 'products' GROUP BY index_name) definitions),
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
   'transaction_isolation', @@global.transaction_isolation));
'@
    $arguments = @('exec', '-e', 'MYSQL_PWD=application', $Container, 'mysql', '--user=application',
        '--database=week4_r01_index_benchmark', '--default-character-set=utf8mb4', '--batch', '--raw',
        '--skip-column-names', '-e', $sql)
    $stderr = Join-Path $Directory ("db-$Label-stderr.log")
    $lines = @(& $docker @arguments 2> $stderr)
    if ($LASTEXITCODE -ne 0) { throw "Read-only DB snapshot failed: $stderr" }
    $snapshot = ($lines -join [Environment]::NewLine) | ConvertFrom-Json
    $snapshot | Add-Member -NotePropertyName CapturedAtUtc -NotePropertyValue ([DateTime]::UtcNow.ToString('o'))
    Write-R02Json $snapshot (Join-Path $Directory ("db-$Label.json"))
    Assert-R02Database $snapshot
    return $snapshot
}

function Start-R02OwnedJava {
    param([string]$RepositoryRoot, [string]$Java, [string]$Jar, [string[]]$Arguments,
        [ValidateSet('Api','JMeter')][string]$Role, [string]$Directory, [string]$CacheMode)
    if (-not (Test-Path -LiteralPath $Directory)) { [void](New-Item -ItemType Directory -Path $Directory) }
    $metadataPath = Join-Path $Directory 'process.json'
    if (Test-Path -LiteralPath $metadataPath) { throw "Process metadata exists: $metadataPath" }
    $nativeArguments = ($Arguments | ForEach-Object { ConvertTo-R02NativeArgument $_ }) -join ' '
    $startParameters = @{
        FilePath = $Java; ArgumentList = $nativeArguments; WorkingDirectory = $RepositoryRoot
        WindowStyle = 'Hidden'; PassThru = $true
        RedirectStandardOutput = (Join-Path $Directory 'stdout.log')
        RedirectStandardError = (Join-Path $Directory 'stderr.log')
    }
    $process = Start-Process @startParameters
    try {
        $info = Get-CimInstance Win32_Process -Filter ("ProcessId = " + $process.Id) -ErrorAction Stop
        if ($null -eq $info -or [string]::IsNullOrWhiteSpace($info.CommandLine)) {
            throw 'Cannot record identity of the Java process just started.'
        }
        $metadata = [pscustomobject][ordered]@{
            FormatVersion = 1; Purpose = 'week4-r02-owned-java'; Role = $Role; CacheMode = $CacheMode
            RepositoryRoot = $RepositoryRoot; ProcessId = [int]$process.Id
            ProcessCreationUtc = $info.CreationDate.ToUniversalTime().ToString('o')
            ExecutablePath = $Java; JarPath = $Jar; JarSha256 = (Get-FileHash -LiteralPath $Jar -Algorithm SHA256).Hash
            CommandLine = $info.CommandLine; Arguments = $Arguments
            ApiPort = 18081; ManagementPort = 18082; MetadataPath = $metadataPath
            StdoutPath = (Join-Path $Directory 'stdout.log'); StderrPath = (Join-Path $Directory 'stderr.log')
            Status = 'Started'; StartedAtUtc = [DateTime]::UtcNow.ToString('o'); StoppedAtUtc = $null; ExitCode = $null
        }
        Write-R02Json $metadata $metadataPath
        return [pscustomobject]@{ Metadata = $metadata; Process = $process }
    } catch {
        # This handle came directly from this Start-Process call; no pre-existing PID is selected.
        if (-not $process.HasExited) { Stop-Process -InputObject $process -Force -ErrorAction SilentlyContinue }
        throw
    }
}

function Assert-R02OwnedProcess {
    param($Metadata, [switch]$AllowExited)
    $expectedRoot = [IO.Path]::GetFullPath((Join-Path $Metadata.RepositoryRoot 'build\benchmark\r02')) +
        [IO.Path]::DirectorySeparatorChar
    if ($Metadata.Purpose -cne 'week4-r02-owned-java' -or $Metadata.FormatVersion -ne 1 -or
        $Metadata.ApiPort -ne 18081 -or $Metadata.ManagementPort -ne 18082 -or
        -not ([IO.Path]::GetFullPath($Metadata.MetadataPath)).StartsWith($expectedRoot, [StringComparison]::OrdinalIgnoreCase)) {
        throw 'Only metadata created inside this R02 output root may identify an owned process.'
    }
    $processIdValue = [int]$Metadata.ProcessId
    if ($processIdValue -le 0) { throw 'Invalid owned process ID.' }
    $info = Get-CimInstance Win32_Process -Filter ("ProcessId = " + $processIdValue) -ErrorAction Stop
    if ($null -eq $info) {
        if ($AllowExited) { return $null }
        throw "Owned Java is not running: $processIdValue"
    }
    if ($info.Name -ine 'java.exe' -or
        -not [string]::Equals($info.ExecutablePath, $Metadata.ExecutablePath, [StringComparison]::OrdinalIgnoreCase) -or
        -not [string]::Equals($info.CommandLine, $Metadata.CommandLine, [StringComparison]::Ordinal) -or
        (Get-R02UtcTicks $info.CreationDate) -ne (Get-R02UtcTicks $Metadata.ProcessCreationUtc) -or
        $info.CommandLine.IndexOf($Metadata.JarPath, [StringComparison]::OrdinalIgnoreCase) -lt 0) {
        throw "Owned PID identity differs. No process was stopped: $processIdValue"
    }
    if ($Metadata.Role -eq 'Api') {
        foreach ($required in @('--server.port=18081', '--management.server.port=18082',
            '--spring.jpa.hibernate.ddl-auto=none', '--benchmark.seed.enabled=false')) {
            if (@($Metadata.Arguments) -notcontains $required) { throw "Missing API ownership argument: $required" }
        }
    } elseif ($Metadata.Role -eq 'JMeter') {
        if (@($Metadata.Arguments) -notcontains '-Jport=18081') { throw 'Owned JMeter is not the R02 workload.' }
    } else { throw 'Unknown owned process role.' }
    return $info
}

function Stop-R02OwnedJava {
    param($Metadata)
    $info = Assert-R02OwnedProcess $Metadata -AllowExited
    if ($null -ne $info) {
        [void](Assert-R02OwnedProcess $Metadata)
        $target = Get-Process -Id ([int]$Metadata.ProcessId) -ErrorAction Stop
        Stop-Process -InputObject $target -Force -ErrorAction Stop
        Wait-Process -Id ([int]$Metadata.ProcessId) -Timeout 10 -ErrorAction SilentlyContinue
        if ($null -ne (Get-CimInstance Win32_Process -Filter ("ProcessId = " + $Metadata.ProcessId))) {
            throw "Owned process has not exited: $($Metadata.ProcessId)"
        }
        $Metadata.Status = 'Stopped'
    } else { $Metadata.Status = 'AlreadyExited' }
    $Metadata.StoppedAtUtc = [DateTime]::UtcNow.ToString('o')
    Write-R02Json $Metadata $Metadata.MetadataPath
}

function Assert-R02ApiListeners {
    param($Metadata)
    [void](Assert-R02OwnedProcess $Metadata)
    $listeners = @(Get-NetTCPConnection -State Listen -ErrorAction Stop |
        Where-Object { $_.LocalPort -in @(18081, 18082) })
    foreach ($portValue in @(18081, 18082)) {
        $matching = @($listeners | Where-Object { $_.LocalPort -eq $portValue })
        if ($matching.Count -eq 0 -or @($matching | Where-Object {
            $_.OwningProcess -ne $Metadata.ProcessId
        }).Count -gt 0) { throw "R02 listener ownership differs on port $portValue" }
    }
}

function Start-R02Api {
    param([string]$RepositoryRoot, [string]$Java, [string]$Jar,
        [ValidateSet('OFF','ON')][string]$CacheMode, [string]$Directory, [switch]$CaptureSql)
    Assert-R02PortsFree
    $enabled = $(if ($CacheMode -eq 'ON') { 'true' } else { 'false' })
    $sqlLevel = $(if ($CaptureSql) { 'DEBUG' } else { 'OFF' })
    $bindLevel = $(if ($CaptureSql) { 'TRACE' } else { 'OFF' })
    $arguments = @('-Dfile.encoding=UTF-8', '-jar', $Jar,
        '--spring.profiles.active=local', '--spring.main.web-application-type=servlet',
        '--server.address=127.0.0.1', '--server.port=18081',
        '--management.server.address=127.0.0.1', '--management.server.port=18082',
        '--datasource.mysql-jpa.main.jdbc-url=jdbc:mysql://localhost:3306/week4_r01_index_benchmark?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true',
        '--datasource.mysql-jpa.main.username=application', '--datasource.mysql-jpa.main.password=application',
        '--spring.jpa.hibernate.ddl-auto=none', '--spring.jpa.show-sql=false',
        '--spring.jpa.properties.hibernate.jdbc.time_zone=UTC',
        '--benchmark.seed.enabled=false', '--scheduler.like-count.enabled=false',
        '--query.product-count-cache.ttl=0s', '--management.health.redis.enabled=false',
        ("--logging.level.org.hibernate.SQL=$sqlLevel"), ("--logging.level.org.hibernate.orm.jdbc.bind=$bindLevel"),
        ("--query.product-detail-cache.enabled=$enabled"),
        '--query.product-detail-cache.maximum-size=1000', '--query.product-detail-cache.ttl=30s')
    $owned = Start-R02OwnedJava $RepositoryRoot $Java $Jar $arguments 'Api' $Directory $CacheMode
    try {
        $owned.Metadata | Add-Member -NotePropertyName CaptureSql -NotePropertyValue ([bool]$CaptureSql)
        Write-R02Json $owned.Metadata $owned.Metadata.MetadataPath
        $timer = [Diagnostics.Stopwatch]::StartNew()
        while ($timer.Elapsed.TotalSeconds -lt 60) {
            if ($owned.Process.HasExited) { throw "R02 API exited during startup: $Directory" }
            $listeners = @(Get-NetTCPConnection -State Listen -ErrorAction Stop |
                Where-Object { $_.LocalPort -in @(18081, 18082) -and $_.OwningProcess -eq $owned.Metadata.ProcessId })
            if (@($listeners | Select-Object -ExpandProperty LocalPort | Select-Object -Unique).Count -eq 2) {
                Assert-R02ApiListeners $owned.Metadata
                $owned.Metadata.Status = 'Listening'
                Write-R02Json $owned.Metadata $owned.Metadata.MetadataPath
                return $owned
            }
            Start-Sleep -Milliseconds 250
        }
        throw "R02 API startup timed out: $Directory"
    } catch {
        Stop-R02OwnedJava $owned.Metadata
        throw
    }
}

function Get-R02MetricValue {
    param([string]$Text, [string]$Name, [string]$Result)
    $found = $false
    $sum = 0.0
    foreach ($line in ($Text -split "\r?\n")) {
        if ($line -match ('^' + [regex]::Escape($Name) + '(?:\{(?<labels>[^}]*)\})?\s+(?<value>[-+0-9.eE]+)(?:\s+\d+)?$')) {
            $labels = $Matches['labels']
            $number = $Matches['value']
            if ($Name -ne 'product_detail_db_loads_total') {
                if ($labels -notmatch '(?:^|,)cache="product-detail"(?:,|$)') { continue }
                if ($Result -and $labels -notmatch ('(?:^|,)result="' + [regex]::Escape($Result) + '"(?:,|$)')) { continue }
            }
            $sum += [double]::Parse($number, [Globalization.CultureInfo]::InvariantCulture)
            $found = $true
        }
    }
    if ($found) { return $sum }
    return $null
}

function Read-R02Metrics {
    param($ApiMetadata, [string]$Path)
    Assert-R02ApiListeners $ApiMetadata
    $response = Invoke-WebRequest -Uri 'http://127.0.0.1:18082/actuator/prometheus' -UseBasicParsing -TimeoutSec 10
    if ($response.StatusCode -ne 200) { throw 'R02 Prometheus endpoint did not return HTTP 200.' }
    $text = $(if ($response.Content -is [byte[]]) { [Text.Encoding]::UTF8.GetString($response.Content) } else { [string]$response.Content })
    $text | Set-Content -LiteralPath $Path -Encoding UTF8
    $snapshot = [pscustomobject][ordered]@{
        CapturedAtUtc = [DateTime]::UtcNow.ToString('o'); Path = $Path
        DbLoads = (Get-R02MetricValue $text 'product_detail_db_loads_total')
        Hits = (Get-R02MetricValue $text 'cache_gets_total' 'hit')
        Misses = (Get-R02MetricValue $text 'cache_gets_total' 'miss')
        Puts = (Get-R02MetricValue $text 'cache_puts_total')
        Evictions = (Get-R02MetricValue $text 'cache_evictions_total')
        Size = (Get-R02MetricValue $text 'cache_size')
    }
    if ($null -eq $snapshot.DbLoads) { throw "Required DB load metric missing: $Path" }
    if ($ApiMetadata.CacheMode -eq 'ON' -and ($null -eq $snapshot.Hits -or $null -eq $snapshot.Misses)) {
        throw "Required ON cache hit/miss metrics missing: $Path"
    }
    return $snapshot
}

function Get-R02MetricDelta {
    param($Before, $After, [long]$Samples)
    $values = [ordered]@{ DbLoads = $null; Hits = $null; Misses = $null; Puts = $null; Evictions = $null }
    foreach ($name in @('DbLoads','Hits','Misses','Puts','Evictions')) {
        if ($null -ne $Before.$name -and $null -ne $After.$name) {
            $values[$name] = $After.$name - $Before.$name
            if ($values[$name] -lt 0) { throw "Metric counter reset during the stage: $name" }
        }
    }
    $gets = $(if ($null -ne $values.Hits -and $null -ne $values.Misses) { $values.Hits + $values.Misses } else { $null })
    $hitRate = $(if ($null -ne $gets -and $gets -gt 0) { $values.Hits / $gets } else { $null })
    return [pscustomobject][ordered]@{
        DbLoads = $values.DbLoads; Hits = $values.Hits; Misses = $values.Misses
        Puts = $values.Puts; Evictions = $values.Evictions; SizeBefore = $Before.Size; SizeAfter = $After.Size
        HitRate = $hitRate; DbLoadsPerRequest = $(if ($Samples -gt 0) { $values.DbLoads / $Samples } else { $null })
        CacheMetricsPresent = ($null -ne $gets)
    }
}
