[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidateSet('before', 'after')]
    [string]$Phase,
    [string]$MySqlContainer = 'docker-mysql-1',
    [string]$OutputDirectory
)

$ErrorActionPreference = 'Stop'
$Phase = $Phase.ToLowerInvariant()
$sourcePath = Join-Path $PSScriptRoot 'http-before\captured-queries.sql'
if ([string]::IsNullOrWhiteSpace($OutputDirectory)) {
    $OutputDirectory = Join-Path $PSScriptRoot "explain-$Phase"
}
$OutputDirectory = [IO.Path]::GetFullPath($OutputDirectory)
if (Test-Path -LiteralPath $OutputDirectory) {
    throw "기존 결과를 덮어쓰지 않습니다: $OutputDirectory"
}
$statements = @(Get-Content -LiteralPath $sourcePath -Encoding UTF8 |
    Where-Object { $_.TrimStart().StartsWith('select ', [StringComparison]::OrdinalIgnoreCase) })
if ($statements.Count -ne 6) {
    throw '캡처한 목록·COUNT SQL 6개가 필요합니다.'
}
$names = @('latest-list', 'latest-count', 'price-list', 'price-count', 'brand7-list', 'brand7-count')
$dockerExecutable = (Get-Command docker -CommandType Application -ErrorAction Stop | Select-Object -First 1).Source
[void](New-Item -ItemType Directory -Path $OutputDirectory)

function Invoke-ReadSql {
    param([string]$Sql, [string]$Name, [switch]$NoColumnNames)

    if ($Sql.TrimStart() -notmatch '^(SELECT|EXPLAIN)\s') {
        throw '실행 계획 수집기는 SELECT·EXPLAIN만 실행합니다.'
    }
    $arguments = @('exec', '-e', 'MYSQL_PWD=application', $MySqlContainer, 'mysql', '--user=application',
        '--database=week4_r01_index_benchmark', '--default-character-set=utf8mb4', '--batch', '--raw')
    if ($NoColumnNames) { $arguments += '--skip-column-names' }
    $arguments += @('-e', $Sql)
    $stderrPath = Join-Path $OutputDirectory "$Name.stderr.log"
    $lines = @(& $dockerExecutable @arguments 2> $stderrPath)
    if ($LASTEXITCODE -ne 0) {
        throw "SQL 수집 실패: $Name (exit=$LASTEXITCODE). 다른 방식으로 재시도하지 않습니다."
    }
    $text = ($lines -join [Environment]::NewLine).TrimEnd([char[]]"`r`n") + [Environment]::NewLine
    [IO.File]::WriteAllText((Join-Path $OutputDirectory "$Name.txt"), $text, [Text.UTF8Encoding]::new($false))
    if ((Get-Item -LiteralPath $stderrPath).Length -eq 0) { Remove-Item -LiteralPath $stderrPath }
    return $lines -join [Environment]::NewLine
}

$snapshotSql = @'
SELECT JSON_OBJECT(
    'DatabaseSchema', DATABASE(), 'MySqlVersion', VERSION(),
    'Products', (SELECT COUNT(*) FROM products),
    'ActiveProducts', (SELECT COUNT(*) FROM products WHERE deleted = FALSE),
    'PriceSum', (SELECT SUM(price) FROM products),
    'StoredLikeCountSum', (SELECT SUM(like_count) FROM products),
    'Likes', (SELECT COUNT(*) FROM product_likes),
    'Indexes', (SELECT JSON_OBJECTAGG(index_name, definition) FROM (
        SELECT index_name, GROUP_CONCAT(CONCAT(column_name, ' ', collation)
            ORDER BY seq_in_index SEPARATOR ',') definition
        FROM information_schema.statistics
        WHERE table_schema = DATABASE() AND table_name = 'products' GROUP BY index_name
    ) definitions),
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
$snapshot = Invoke-ReadSql $snapshotSql 'snapshot' -NoColumnNames | ConvertFrom-Json
if ($snapshot.DatabaseSchema -ne 'week4_r01_index_benchmark' -or $snapshot.MySqlVersion -ne '8.0.46' -or
    $snapshot.Settings.innodb_buffer_pool_size -ne 134217728 -or $snapshot.Products -ne 1000000 -or
    $snapshot.ActiveProducts -ne 950400 -or $snapshot.PriceSum -ne 50950000000 -or
    $snapshot.StoredLikeCountSum -ne 2597980 -or $snapshot.Likes -ne 2597980) {
    throw '전용 DB·데이터·128MiB 기준이 다릅니다. 설정 변경 없이 중단합니다.'
}
$expectedIndexes = @{
    PRIMARY = 'id A'
    idx_products_deleted_brand_created = 'deleted A,brand_id A,created_at D,id D'
    idx_products_deleted_brand_price = 'deleted A,brand_id A,price A,id D'
    idx_products_deleted_like = 'deleted A,like_count D,id D'
    idx_products_deleted_brand_like = 'deleted A,brand_id A,like_count D,id D'
}
if ($Phase -eq 'after') {
    $expectedIndexes['idx_products_deleted_created'] = 'deleted A,created_at D,id D'
    $expectedIndexes['idx_products_deleted_price'] = 'deleted A,price A,id D'
}
$actualIndexes = @($snapshot.Indexes.PSObject.Properties)
if ($actualIndexes.Count -ne $expectedIndexes.Count) { throw '예상한 인덱스 개수와 다릅니다.' }
foreach ($name in $expectedIndexes.Keys) {
    if ($snapshot.Indexes.$name -cne $expectedIndexes[$name]) { throw "인덱스 정의가 다릅니다: $name" }
}
$snapshot | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath (Join-Path $OutputDirectory 'snapshot.json') -Encoding UTF8
$records = [Collections.Generic.List[object]]::new()
for ($index = 0; $index -lt $statements.Count; $index++) {
    $name = $names[$index]
    $sql = $statements[$index].Trim()
    Write-Host "$Phase / $name : 준비 실행 -> EXPLAIN -> EXPLAIN ANALYZE"
    $timer = [Diagnostics.Stopwatch]::StartNew()
    $null = Invoke-ReadSql $sql "$name-warmup"
    $null = Invoke-ReadSql ('EXPLAIN ' + $sql) "$name-explain"
    $null = Invoke-ReadSql ('EXPLAIN ANALYZE ' + $sql) "$name-analyze"
    $records.Add([pscustomobject]@{ Name=$name; Sql=$sql; WallSeconds=[Math]::Round($timer.Elapsed.TotalSeconds,3) })
}
[pscustomobject]@{
    Phase=$Phase; CompletedAtUtc=[DateTime]::UtcNow.ToString('o'); Queries=$records
    PreparationRunsPerQuery=1; ExplainRunsPerQuery=1; AnalyzeRunsPerQuery=1
    AnalyzeTableExecuted=$false; OutputDirectory=$OutputDirectory
} | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath (Join-Path $OutputDirectory 'capture.json') -Encoding UTF8
Write-Host "실행 계획 수집 완료: $OutputDirectory"
