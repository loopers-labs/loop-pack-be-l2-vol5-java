[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$ManifestPath,
    [string]$OutputDirectory
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version 2
$repositoryRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..\..\..'))

function Read-SummaryJson {
    param([string]$Path)
    return (Get-Content -LiteralPath $Path -Raw -Encoding UTF8 | ConvertFrom-Json)
}

function Assert-SummaryNumber {
    param([AllowNull()]$Actual, [AllowNull()]$Expected, [string]$Label)
    if ($null -eq $Actual -or $null -eq $Expected) {
        if ($null -ne $Actual -or $null -ne $Expected) { throw "Nullable number differs: $Label" }
        return
    }
    $actualNumber = [double]$Actual; $expectedNumber = [double]$Expected
    if ([double]::IsNaN($actualNumber) -or [double]::IsInfinity($actualNumber) -or
        [double]::IsNaN($expectedNumber) -or [double]::IsInfinity($expectedNumber) -or
        [Math]::Abs($actualNumber - $expectedNumber) -gt [Math]::Max(0.000001, [Math]::Abs($expectedNumber) * 0.000000001)) {
        throw "Number differs: $Label (expected=$Expected, actual=$Actual)"
    }
}

function Get-SummaryRelativePath {
    param([string]$Path)
    $full = [IO.Path]::GetFullPath($Path)
    $prefix = $repositoryRoot.TrimEnd([char[]]@('\','/')) + [IO.Path]::DirectorySeparatorChar
    if (-not $full.StartsWith($prefix, [StringComparison]::OrdinalIgnoreCase)) {
        throw "Evidence must remain inside this repository: $Path"
    }
    return $full.Substring($prefix.Length).Replace('\','/')
}

function Get-SummaryArtifact {
    param([string]$Path)
    $file = Get-Item -LiteralPath $Path -ErrorAction Stop
    if ($file.PSIsContainer -or $file.Length -eq 0) { throw "Evidence is empty or not a file: $Path" }
    return [pscustomobject][ordered]@{
        Path = (Get-SummaryRelativePath $file.FullName)
        Bytes = $file.Length
        Sha256 = (Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash
    }
}

function Assert-SummaryIds {
    param($Actual, $Expected, [string]$Label)
    $actualIds = @($Actual | ForEach-Object { [long]$_ } | Sort-Object)
    $expectedIds = @($Expected | ForEach-Object { [long]$_ } | Sort-Object)
    if ($actualIds.Count -ne 100 -or @($actualIds | Select-Object -Unique).Count -ne 100 -or
        ($actualIds -join ',') -cne ($expectedIds -join ',')) {
        throw "Expected the same 100 distinct workload IDs: $Label"
    }
}

function Read-SummaryJtl {
    param([string]$Path)
    if ($null -eq ('Loopers.Benchmark.R02.JtlCsvReader' -as [type])) {
        Add-Type -Language CSharp -ErrorAction Stop -TypeDefinition @'
using System;
using System.Collections.Generic;
using System.Globalization;
using System.IO;
using System.Text;

namespace Loopers.Benchmark.R02
{
    public sealed class JtlSummary
    {
        public long Samples { get; set; }
        public long FailedSamples { get; set; }
        public double ErrorRate { get; set; }
        public double AverageMs { get; set; }
        public double P50Ms { get; set; }
        public double P95Ms { get; set; }
        public double P99Ms { get; set; }
        public double ThroughputPerSecond { get; set; }
        public double SampleSpanSeconds { get; set; }
        public int UniqueIds { get; set; }
        public long[] ObservedIds { get; set; }
    }

    public static class JtlCsvReader
    {
        private static readonly CultureInfo Invariant = CultureInfo.InvariantCulture;

        // Physical line reader retains the actual CRLF/LF/CR terminator for quoted multiline fields.
        private sealed class CsvLines : IDisposable
        {
            private readonly StreamReader reader;
            private readonly char[] buffer = new char[65536];
            private int position;
            private int length;

            public CsvLines(string path)
            {
                reader = new StreamReader(path, new UTF8Encoding(false, true), true, 65536);
            }

            private bool Fill()
            {
                length = reader.Read(buffer, 0, buffer.Length);
                position = 0;
                return length != 0;
            }

            private int Peek()
            {
                if (position == length && !Fill()) return -1;
                return buffer[position];
            }

            public string Read(out string ending)
            {
                ending = "";
                if (Peek() == -1) return null;
                StringBuilder partial = null;
                for (;;)
                {
                    int start = position;
                    int end = start;
                    while (end < length && buffer[end] != '\r' && buffer[end] != '\n') end++;
                    if (end < length)
                    {
                        string line;
                        if (partial == null) line = new string(buffer, start, end - start);
                        else
                        {
                            partial.Append(buffer, start, end - start);
                            line = partial.ToString();
                        }
                        char terminator = buffer[end];
                        position = end + 1;
                        if (terminator == '\r')
                        {
                            if (Peek() == '\n') { position++; ending = "\r\n"; }
                            else ending = "\r";
                        }
                        else ending = "\n";
                        return line;
                    }
                    if (partial == null) partial = new StringBuilder();
                    partial.Append(buffer, start, length - start);
                    position = length;
                    if (!Fill()) return partial.ToString();
                }
            }

            public void Dispose() { reader.Dispose(); }
        }

        private static string[] ReadRecord(CsvLines source)
        {
            string ending;
            string line = source.Read(out ending);
            if (line == null) return null;
            // JMeter success rows normally have no quoted fields.
            if (line.IndexOf('"') < 0) return line.Split(',');

            List<string> fields = new List<string>();
            StringBuilder value = new StringBuilder();
            bool quoted = false;
            bool closed = false;
            bool started = false;
            for (;;)
            {
                for (int index = 0; index < line.Length; index++)
                {
                    char current = line[index];
                    if (quoted)
                    {
                        if (current == '"')
                        {
                            if (index + 1 < line.Length && line[index + 1] == '"')
                            {
                                value.Append('"');
                                index++;
                            }
                            else { quoted = false; closed = true; }
                        }
                        else value.Append(current);
                    }
                    else if (closed)
                    {
                        if (current != ',') throw new InvalidDataException("Characters after a closing CSV quote.");
                        fields.Add(value.ToString());
                        value.Clear(); closed = false; started = false;
                    }
                    else if (current == ',')
                    {
                        fields.Add(value.ToString());
                        value.Clear(); started = false;
                    }
                    else if (current == '"')
                    {
                        if (started) throw new InvalidDataException("Quote inside an unquoted CSV field.");
                        quoted = true; started = true;
                    }
                    else { value.Append(current); started = true; }
                }
                if (!quoted)
                {
                    fields.Add(value.ToString());
                    return fields.ToArray();
                }
                if (ending.Length == 0) throw new InvalidDataException("Unterminated quoted CSV field.");
                value.Append(ending);
                line = source.Read(out ending);
                if (line == null) throw new InvalidDataException("Unterminated quoted CSV field.");
            }
        }

        public static JtlSummary Read(string path)
        {
            long count = 0;
            long errors = 0;
            double sum = 0;
            long first = long.MaxValue;
            long last = 0;
            List<double> elapsedValues = new List<double>();
            HashSet<long> seenIds = new HashSet<long>();

            using (CsvLines source = new CsvLines(path))
            {
                string[] header = ReadRecord(source);
                if (header == null) throw new InvalidDataException("Empty JTL: " + path);
                Dictionary<string, int> columns = new Dictionary<string, int>(StringComparer.Ordinal);
                for (int index = 0; index < header.Length; index++)
                {
                    if (columns.ContainsKey(header[index])) throw new InvalidDataException("Duplicate JTL header.");
                    columns.Add(header[index], index);
                }
                string[] required = { "timeStamp", "elapsed", "label", "responseCode", "success", "productId", "URL" };
                foreach (string name in required)
                    if (!columns.ContainsKey(name)) throw new InvalidDataException("Missing JTL column: " + name);
                int timestampColumn = columns["timeStamp"];
                int elapsedColumn = columns["elapsed"];
                int labelColumn = columns["label"];
                int codeColumn = columns["responseCode"];
                int successColumn = columns["success"];
                int idColumn = columns["productId"];
                int urlColumn = columns["URL"];

                string[] row;
                while ((row = ReadRecord(source)) != null)
                {
                    count++;
                    if (row.Length != header.Length) throw new InvalidDataException("JTL field count differs at record " + count);
                    if (row[successColumn] != "true" || row[codeColumn] != "200") errors++;
                    if (row[labelColumn] != "product-detail") throw new InvalidDataException("Unexpected JTL sampler label.");
                    long start;
                    long id;
                    double elapsed;
                    if (!long.TryParse(row[timestampColumn], NumberStyles.Integer, Invariant, out start) ||
                        !long.TryParse(row[idColumn], NumberStyles.Integer, Invariant, out id) ||
                        !double.TryParse(row[elapsedColumn], NumberStyles.Float, Invariant, out elapsed) ||
                        id <= 0 || elapsed < 0 || double.IsNaN(elapsed) || double.IsInfinity(elapsed))
                        throw new InvalidDataException("Invalid JTL elapsed/timestamp/ID at record " + count);

                    Uri url;
                    if (!Uri.TryCreate(row[urlColumn], UriKind.Absolute, out url) ||
                        url.Scheme != "http" ||
                        !(string.Equals(url.Host, "localhost", StringComparison.OrdinalIgnoreCase) ||
                          string.Equals(url.Host, "127.0.0.1", StringComparison.OrdinalIgnoreCase)) ||
                        url.Port != 18081 ||
                        url.AbsolutePath != "/api/v1/products/" + id.ToString(Invariant) ||
                        url.Query.Length != 0)
                        throw new InvalidDataException("JTL request URL differs from its product ID at record " + count);

                    long end = checked(start + checked((long)Math.Ceiling(elapsed)));
                    if (start < first) first = start;
                    if (end > last) last = end;
                    sum += elapsed;
                    elapsedValues.Add(elapsed);
                    seenIds.Add(id);
                }
            }
            if (count <= 1 || errors != 0 || last <= first)
                throw new InvalidDataException("JTL requires multiple successful samples and a positive sample span.");
            elapsedValues.Sort();
            long[] observed = new long[seenIds.Count];
            seenIds.CopyTo(observed);
            Array.Sort(observed);
            double span = (last - first) / 1000.0;
            // Zero mean is valid. No ratio divides by mean response time.
            return new JtlSummary
            {
                Samples = count,
                FailedSamples = errors,
                ErrorRate = errors / (double)count,
                AverageMs = sum / count,
                P50Ms = elapsedValues[(int)Math.Ceiling(count * 0.50) - 1],
                P95Ms = elapsedValues[(int)Math.Ceiling(count * 0.95) - 1],
                P99Ms = elapsedValues[(int)Math.Ceiling(count * 0.99) - 1],
                ThroughputPerSecond = count / span,
                SampleSpanSeconds = span,
                UniqueIds = observed.Length,
                ObservedIds = observed
            };
        }
    }
}
'@
    }
    return [Loopers.Benchmark.R02.JtlCsvReader]::Read($Path)
}

function Get-SummaryMetricValue {
    param([string]$Text, [string]$Name, [string]$Result)
    $found = $false; $sum = 0.0
    foreach ($line in ($Text -split "\r?\n")) {
        if ($line -match ('^' + [regex]::Escape($Name) + '(?:\{(?<labels>[^}]*)\})?\s+(?<value>[-+0-9.eE]+)(?:\s+\d+)?$')) {
            $labels = $Matches['labels']; $number = $Matches['value']
            if ($Name -ne 'product_detail_db_loads_total') {
                if ($labels -notmatch '(?:^|,)cache="product-detail"(?:,|$)') { continue }
                if ($Result -and $labels -notmatch ('(?:^|,)result="' + [regex]::Escape($Result) + '"(?:,|$)')) { continue }
            }
            $parsed = [double]::Parse($number, [Globalization.CultureInfo]::InvariantCulture)
            if ([double]::IsNaN($parsed) -or [double]::IsInfinity($parsed) -or $parsed -lt 0) {
                throw "Metric is not a finite nonnegative number: $Name"
            }
            $sum += $parsed; $found = $true
        }
    }
    if ($found) { return $sum }
    return $null
}

function Read-SummaryMetrics {
    param([string]$Path)
    $text = Get-Content -LiteralPath $Path -Raw -Encoding UTF8
    $result = [pscustomobject][ordered]@{
        DbLoads = (Get-SummaryMetricValue $text 'product_detail_db_loads_total')
        Hits = (Get-SummaryMetricValue $text 'cache_gets_total' 'hit')
        Misses = (Get-SummaryMetricValue $text 'cache_gets_total' 'miss')
        Puts = (Get-SummaryMetricValue $text 'cache_puts_total')
        Evictions = (Get-SummaryMetricValue $text 'cache_evictions_total')
        Size = (Get-SummaryMetricValue $text 'cache_size')
    }
    if ($null -eq $result.DbLoads) { throw "Required DB counter is missing: $Path" }
    return $result
}

function Get-SummaryMetricDelta {
    param($Before, $After, [long]$Samples, [ValidateSet('OFF','ON')][string]$CacheMode)
    if ($Samples -le 0) { throw 'Cannot calculate metrics without samples.' }
    $delta = [ordered]@{ DbLoads = $null; Hits = $null; Misses = $null; Puts = $null; Evictions = $null }
    foreach ($field in @('DbLoads','Hits','Misses','Puts','Evictions')) {
        if (($null -eq $Before.$field) -ne ($null -eq $After.$field)) { throw "Metric presence changed: $field" }
        if ($null -ne $Before.$field) {
            $delta[$field] = $After.$field - $Before.$field
            if ($delta[$field] -lt 0) { throw "Metric reset: $field" }
        }
    }
    if ($CacheMode -eq 'OFF') {
        Assert-SummaryNumber $delta.DbLoads $Samples 'OFF DB loads = requests'
    } else {
        if ($null -eq $delta.Hits -or $null -eq $delta.Misses) { throw 'ON requires hit and miss counters.' }
        Assert-SummaryNumber ($delta.Hits + $delta.Misses) $Samples 'ON hits + misses = requests'
        Assert-SummaryNumber $delta.DbLoads $delta.Misses 'ON DB loads = misses'
    }
    $gets = $(if ($null -ne $delta.Hits -and $null -ne $delta.Misses) { $delta.Hits + $delta.Misses } else { $null })
    return [pscustomobject][ordered]@{
        DbLoads = $delta.DbLoads; Hits = $delta.Hits; Misses = $delta.Misses
        Puts = $delta.Puts; Evictions = $delta.Evictions; SizeBefore = $Before.Size; SizeAfter = $After.Size
        HitRate = $(if ($null -ne $gets -and $gets -gt 0) { $delta.Hits / $gets } else { $null })
        DbLoadsPerRequest = $delta.DbLoads / $Samples
        CacheMetricsPresent = ($null -ne $gets)
    }
}

function Assert-SummaryDatabase {
    param($Snapshot)
    $numbers = [ordered]@{
        Products = 1000000; ActiveProducts = 950400; Likes = 2597980; StoredLikes = 2597980; PriceSum = 50950000000
        Brands = 1000; Users = 1000; FixtureUsers = 2; Wallets = 1000; FixtureWallets = 2
        WalletBalanceSum = 0; WalletsWithNonzeroBalance = 0
    }
    if ($Snapshot.DatabaseSchema -cne 'week4_r01_index_benchmark' -or $Snapshot.MySqlVersion -cne '8.0.46') {
        throw 'Source DB schema/version differs.'
    }
    foreach ($field in $numbers.Keys) { Assert-SummaryNumber $Snapshot.$field $numbers[$field] ("DB $field") }
    $indexes = [ordered]@{
        PRIMARY = 'id A'
        idx_products_deleted_brand_created = 'deleted A,brand_id A,created_at D,id D'
        idx_products_deleted_brand_price = 'deleted A,brand_id A,price A,id D'
        idx_products_deleted_like = 'deleted A,like_count D,id D'
        idx_products_deleted_brand_like = 'deleted A,brand_id A,like_count D,id D'
        idx_products_deleted_created = 'deleted A,created_at D,id D'
        idx_products_deleted_price = 'deleted A,price A,id D'
    }
    $settings = [ordered]@{
        autocommit = '1'; innodb_buffer_pool_size = '134217728'; innodb_flush_log_at_trx_commit = '1'
        innodb_redo_log_capacity = '104857600'; join_buffer_size = '262144'; max_heap_table_size = '16777216'
        sort_buffer_size = '262144'
        sql_mode = 'ONLY_FULL_GROUP_BY,STRICT_TRANS_TABLES,NO_ZERO_IN_DATE,NO_ZERO_DATE,ERROR_FOR_DIVISION_BY_ZERO,NO_ENGINE_SUBSTITUTION'
        sync_binlog = '1'; tmp_table_size = '16777216'; transaction_isolation = 'REPEATABLE-READ'
    }
    if (@($Snapshot.ProductIndexes.PSObject.Properties).Count -ne $indexes.Count -or
        @($Snapshot.Settings.PSObject.Properties).Count -ne $settings.Count) { throw 'Index/setting count differs.' }
    foreach ($name in $indexes.Keys) {
        if ([string]$Snapshot.ProductIndexes.$name -cne $indexes[$name]) { throw "Source index differs: $name" }
    }
    foreach ($name in $settings.Keys) {
        if ([string]$Snapshot.Settings.$name -cne $settings[$name]) { throw "Source DB setting differs: $name" }
    }
}

function Assert-SummarySameJson {
    param($Actual, $Expected, [string]$Label)
    if (($Actual | ConvertTo-Json -Depth 20 -Compress) -cne ($Expected | ConvertTo-Json -Depth 20 -Compress)) {
        throw "Recorded JSON differs: $Label"
    }
}

function Assert-SummaryArgument {
    param($Arguments, [string]$Name, [string]$Value)
    $prefix = $Name + '='
    $matching = @($Arguments | Where-Object { $_.StartsWith($prefix, [StringComparison]::Ordinal) })
    if ($matching.Count -ne 1 -or $matching[0] -cne ($prefix + $Value)) { throw "Recorded CLI argument differs: $Name" }
}

function Assert-SummaryStageMetrics {
    param($Recorded, $Calculated, [string]$Label)
    foreach ($field in @('DbLoads','Hits','Misses','Puts','Evictions','SizeBefore','SizeAfter','HitRate','DbLoadsPerRequest')) {
        Assert-SummaryNumber $Recorded.$field $Calculated.$field ("$Label $field")
    }
    if ($Recorded.CacheMetricsPresent -ne $Calculated.CacheMetricsPresent) { throw "Metric presence differs: $Label" }
}

if (-not (Test-Path -LiteralPath (Join-Path $repositoryRoot 'gradlew.bat') -PathType Leaf)) { throw 'Repository root not found.' }
$ManifestPath = (Resolve-Path -LiteralPath $ManifestPath -ErrorAction Stop).Path
$manifestArtifact = Get-SummaryArtifact $ManifestPath
$sessionDirectory = Split-Path $ManifestPath
$rawPrefix = [IO.Path]::GetFullPath((Join-Path $repositoryRoot 'build\benchmark\r02')) + [IO.Path]::DirectorySeparatorChar
if (-not $ManifestPath.StartsWith($rawPrefix, [StringComparison]::OrdinalIgnoreCase)) {
    throw 'Only a source manifest under build/benchmark/r02 is supported.'
}
$manifest = Read-SummaryJson $ManifestPath
if ($manifest.FormatVersion -ne 1 -or $manifest.Purpose -cne 'week4-r02-product-detail-cache-benchmark' -or
    $manifest.Mode -cne 'Measure' -or $manifest.Status -cne 'Finished' -or $null -ne $manifest.Error -or
    @($manifest.CleanupErrors).Count -ne 0 -or
    -not [string]::Equals($manifest.RepositoryRoot, $repositoryRoot, [StringComparison]::OrdinalIgnoreCase) -or
    -not [string]::Equals($manifest.OutputDirectory, $sessionDirectory, [StringComparison]::OrdinalIgnoreCase)) {
    throw 'A completed, successful Measure manifest from this repository is required.'
}
foreach ($condition in @(
    @('WarmupSeconds',20), @('MeasurementSeconds',60), @('RampupSeconds',5), @('Repetitions',2),
    @('ConnectTimeoutMs',3000), @('ResponseTimeoutMs',30000), @('CacheMaximumSize',1000),
    @('ApiPort',18081), @('ManagementPort',18082))) {
    Assert-SummaryNumber $manifest.($condition[0]) $condition[1] $condition[0]
}
if ($manifest.CacheTtl -cne '30s' -or (@($manifest.Users) -join ',') -cne '1,10' -or
    (@($manifest.ProtectedPorts) -join ',') -cne '18080,8081') { throw 'Source workload configuration differs.' }
if ([string]::IsNullOrWhiteSpace($manifest.StartedAtUtc) -or [string]::IsNullOrWhiteSpace($manifest.FinishedAtUtc) -or
    $manifest.CodeGitSha -notmatch '^[0-9a-fA-F]{40}$') { throw 'Source run times/Git SHA are missing.' }
$jarArtifact = Get-SummaryArtifact $manifest.JarPath
if ($jarArtifact.Sha256 -cne $manifest.JarSha256) { throw 'Source application JAR hash changed.' }
$jmeterJarArtifact = Get-SummaryArtifact (Join-Path $manifest.JMeterHome 'bin\ApacheJMeter.jar')
if ($jmeterJarArtifact.Sha256 -cne $manifest.JMeterJarSha256) { throw 'Source JMeter JAR hash changed.' }
$inputArtifacts = [Collections.Generic.List[object]]::new()
foreach ($inputRecord in $manifest.Inputs) {
    $artifact = Get-SummaryArtifact $inputRecord.Path
    if ($artifact.Sha256 -cne $inputRecord.Sha256) { throw "Frozen input hash changed: $($artifact.Path)" }
    $inputArtifacts.Add($artifact)
}
$expectedInputNames = @('benchmark-common.ps1','expected-responses.json','product-detail.jmx','product-ids.csv',
    'results.properties','run-benchmark.ps1') | Sort-Object
$actualInputNames = @($manifest.Inputs | ForEach-Object { [IO.Path]::GetFileName($_.Path) } | Sort-Object)
if (($actualInputNames -join ',') -cne ($expectedInputNames -join ',')) { throw 'Expected the six frozen measurement inputs.' }
$csvInput = $manifest.Inputs | Where-Object { [IO.Path]::GetFileName($_.Path) -ceq 'product-ids.csv' }
$ids = @(Import-Csv -LiteralPath $csvInput.Path -Encoding UTF8 | ForEach-Object { [long]$_.productId })
Assert-SummaryIds $manifest.WorkloadIds $ids 'manifest and CSV'
$dbBefore = Read-SummaryJson (Join-Path $sessionDirectory 'db-before.json')
$dbAfter = Read-SummaryJson (Join-Path $sessionDirectory 'db-after.json')
Assert-SummaryDatabase $dbBefore; Assert-SummaryDatabase $dbAfter
Assert-SummarySameJson $dbBefore $manifest.DatabaseBefore 'DB before file/manifest'
Assert-SummarySameJson $dbAfter $manifest.DatabaseAfter 'DB after file/manifest'
Assert-SummarySameJson $dbBefore.Settings $dbAfter.Settings 'DB settings before/after'
Assert-SummarySameJson $dbBefore.ProductIndexes $dbAfter.ProductIndexes 'product indexes before/after'
$protectedBefore = Get-SummaryArtifact (Join-Path $sessionDirectory 'protected-listeners-before.json')
$protectedAfter = Get-SummaryArtifact (Join-Path $sessionDirectory 'protected-listeners-after.json')
if ($protectedBefore.Sha256 -cne $protectedAfter.Sha256) { throw 'Protected listener source bytes changed.' }
$expectedOrder = @('r1-OFF-u1','r1-OFF-u10','r1-ON-u1','r1-ON-u10',
    'r2-ON-u1','r2-ON-u10','r2-OFF-u1','r2-OFF-u10')
if ((@($manifest.MeasurementOrder) -join ',') -cne ($expectedOrder -join ',')) { throw 'Source planned order differs.' }
if (@($manifest.Runs).Count -ne 16 -or @($manifest.Processes).Count -ne 4) { throw 'Expected 16 stages and four API processes.' }
$apiModes = @('OFF','ON','ON','OFF')
for ($index = 0; $index -lt 4; $index++) {
    $api = $manifest.Processes[$index]
    $diskApi = Read-SummaryJson $api.MetadataPath
    Assert-SummarySameJson $diskApi $api 'API final metadata'
    if ($api.Role -cne 'Api' -or $api.Status -cne 'Stopped' -or $api.CacheMode -cne $apiModes[$index] -or
        $api.JarSha256 -cne $manifest.JarSha256 -or $api.JarPath -cne $manifest.JarPath -or $api.CaptureSql -or
        [string]::IsNullOrWhiteSpace($api.StoppedAtUtc)) { throw 'All four APIs must be stopped, SQL OFF, and use the same JAR.' }
    $requiredApiArguments = [ordered]@{
        '--server.port' = '18081'; '--management.server.port' = '18082'
        '--spring.profiles.active' = 'local'; '--spring.jpa.hibernate.ddl-auto' = 'none'
        '--benchmark.seed.enabled' = 'false'; '--scheduler.like-count.enabled' = 'false'
        '--query.product-count-cache.ttl' = '0s'; '--management.health.redis.enabled' = 'false'
        '--logging.level.org.hibernate.SQL' = 'OFF'; '--logging.level.org.hibernate.orm.jdbc.bind' = 'OFF'
        '--query.product-detail-cache.enabled' = $(if ($api.CacheMode -eq 'ON') {'true'} else {'false'})
        '--query.product-detail-cache.maximum-size' = '1000'; '--query.product-detail-cache.ttl' = '30s'
    }
    foreach ($name in $requiredApiArguments.Keys) { Assert-SummaryArgument $api.Arguments $name $requiredApiArguments[$name] }
}
$rawJtls = [Collections.Generic.List[object]]::new()
$rawStatistics = [Collections.Generic.List[object]]::new()
$rawMetrics = [Collections.Generic.List[object]]::new()
$measurements = [Collections.Generic.List[object]]::new()
$warmups = [Collections.Generic.List[object]]::new()
$statisticsCopies = [Collections.Generic.List[object]]::new()
for ($index = 0; $index -lt 16; $index++) {
    $run = $manifest.Runs[$index]
    $stage = $(if ($index % 2 -eq 0) { 'warmup' } else { 'measurement' })
    $runKey = 'r{0}-{1}-u{2}' -f $run.Repetition, $run.CacheMode, $run.Users
    if ($runKey -cne $expectedOrder[[int][Math]::Floor($index / 2)] -or $run.Stage -cne $stage -or
        $run.Status -cne 'Finished' -or $run.ExitCode -ne 0 -or $null -ne $run.Error -or $null -ne $run.CleanupError -or
        $run.Validation -cne 'light' -or $run.RampupSeconds -ne 5 -or $run.Loops -ne -1 -or
        $run.Seconds -ne $(if ($stage -eq 'warmup') {20} else {60}) -or
        $run.Process.Role -cne 'JMeter' -or $run.Process.Status -cne 'Exited' -or $run.Process.ExitCode -ne 0 -or
        $run.Process.JarSha256 -cne $manifest.JMeterJarSha256) { throw "Source stage contract differs: $runKey/$stage" }
    $directory = [IO.Path]::GetFullPath($run.Directory)
    if (-not $directory.StartsWith(($sessionDirectory + [IO.Path]::DirectorySeparatorChar),
        [StringComparison]::OrdinalIgnoreCase)) { throw 'Stage directory is outside the source session.' }
    $stageRecord = Read-SummaryJson (Join-Path $directory 'stage.json')
    Assert-SummarySameJson $stageRecord $run 'final stage file/manifest'
    Assert-SummarySameJson (Read-SummaryJson $run.Process.MetadataPath) $run.Process 'JMeter final process metadata'
    $requiredJmeterArguments = [ordered]@{
        '-Jhost' = 'localhost'; '-Jport' = '18081'; '-Jthreads' = [string]$run.Users
        '-Jrampup' = '5'; '-Jloops' = '-1'; '-Jscheduler' = 'true'; '-Jduration' = [string]$run.Seconds
        '-JconnectTimeout' = '3000'; '-JresponseTimeout' = '30000'; '-Jvalidation' = 'light'
        '-JidsFile' = $csvInput.Path
    }
    foreach ($name in $requiredJmeterArguments.Keys) {
        Assert-SummaryArgument $run.JMeterArguments $name $requiredJmeterArguments[$name]
        Assert-SummaryArgument $run.Process.Arguments $name $requiredJmeterArguments[$name]
    }
    $jtlPath = Join-Path $directory 'samples.jtl'
    $jtl = Read-SummaryJtl $jtlPath
    Assert-SummaryIds $jtl.ObservedIds $ids ("$runKey/$stage raw IDs")
    Assert-SummaryIds $run.Samples.ObservedIds $ids ("$runKey/$stage manifest IDs")
    foreach ($field in @('Samples','FailedSamples','ErrorRate','AverageMs','P50Ms','P95Ms','P99Ms',
        'ThroughputPerSecond','SampleSpanSeconds','UniqueIds')) {
        Assert-SummaryNumber $run.Samples.$field $jtl.$field ("$runKey/$stage $field")
    }
    $jtlArtifact = Get-SummaryArtifact $jtlPath
    $rawJtls.Add($jtlArtifact)
    $beforePath = Join-Path $directory 'metrics-before.prom'; $afterPath = Join-Path $directory 'metrics-after.prom'
    $metricsBefore = Read-SummaryMetrics $beforePath; $metricsAfter = Read-SummaryMetrics $afterPath
    foreach ($field in @('DbLoads','Hits','Misses','Puts','Evictions','Size')) {
        Assert-SummaryNumber $run.MetricsBefore.$field $metricsBefore.$field ("$runKey/$stage before $field")
        Assert-SummaryNumber $run.MetricsAfter.$field $metricsAfter.$field ("$runKey/$stage after $field")
    }
    $delta = Get-SummaryMetricDelta $metricsBefore $metricsAfter $jtl.Samples $run.CacheMode
    Assert-SummaryStageMetrics $run.MetricDelta $delta ("$runKey/$stage manifest")
    Assert-SummaryStageMetrics (Read-SummaryJson (Join-Path $directory 'metrics-delta.json')) $delta ("$runKey/$stage file")
    $metricBeforeArtifact = Get-SummaryArtifact $beforePath; $metricAfterArtifact = Get-SummaryArtifact $afterPath
    $rawMetrics.Add($metricBeforeArtifact); $rawMetrics.Add($metricAfterArtifact)
    $row = [pscustomobject][ordered]@{
        Run = $runKey; Repetition = $run.Repetition; CacheMode = $run.CacheMode; Users = $run.Users; Stage = $stage
        StartedAtUtc = $run.StartedAtUtc; FinishedAtUtc = $run.FinishedAtUtc; WallElapsedSeconds = $run.ElapsedSeconds
        ScheduledSeconds = $run.Seconds; Samples = $jtl.Samples; FailedSamples = $jtl.FailedSamples; ErrorRate = $jtl.ErrorRate
        AverageMs = $jtl.AverageMs; P50Ms = $jtl.P50Ms; P95Ms = $jtl.P95Ms; P99Ms = $jtl.P99Ms
        ThroughputPerSecond = $jtl.ThroughputPerSecond; SampleSpanSeconds = $jtl.SampleSpanSeconds; UniqueIds = $jtl.UniqueIds
        Metrics = $delta; Jtl = $jtlArtifact
        MetricBefore = $metricBeforeArtifact; MetricAfter = $metricAfterArtifact; HtmlStatistics = $null
    }
    if ($stage -eq 'measurement') {
        $htmlPath = Join-Path $directory 'html'
        [void](Get-SummaryArtifact (Join-Path $htmlPath 'index.html'))
        $statisticsPath = Join-Path $htmlPath 'statistics.json'
        $statisticsText = Get-Content -LiteralPath $statisticsPath -Raw -Encoding UTF8
        if ($statisticsText -match '(?i)[A-Z]:\\\\' -or $statisticsText -match '(?i)\bCommandLine\b|InheritedJavaEnvironment') {
            throw 'Statistics unexpectedly contains private path/process metadata.'
        }
        $statistics = Read-SummaryJson $statisticsPath
        $labels = @($statistics.PSObject.Properties.Name)
        if ($labels.Count -ne 2 -or $labels -cnotcontains 'Total' -or $labels -cnotcontains 'product-detail') {
            throw 'Unexpected JMeter statistics labels.'
        }
        foreach ($label in @('Total','product-detail')) {
            $values = $statistics | Select-Object -ExpandProperty $label
            if ($values.transaction -cne $label) { throw 'Unexpected transaction label in statistics.' }
            Assert-SummaryNumber $values.sampleCount $jtl.Samples ("$runKey HTML $label samples")
            Assert-SummaryNumber $values.errorCount $jtl.FailedSamples ("$runKey HTML $label errors")
            Assert-SummaryNumber $values.errorPct 0 ("$runKey HTML $label error percentage")
            Assert-SummaryNumber $values.meanResTime $jtl.AverageMs ("$runKey HTML $label average")
            Assert-SummaryNumber $values.throughput $jtl.ThroughputPerSecond ("$runKey HTML $label throughput")
            # Percentiles intentionally use raw JTL nearest-rank, not HTML interpolation.
        }
        $statisticsArtifact = Get-SummaryArtifact $statisticsPath
        $rawStatistics.Add($statisticsArtifact)
        $fileName = ('r{0}-{1}-u{2}.json' -f $run.Repetition, $run.CacheMode.ToLowerInvariant(), $run.Users)
        $statisticsCopies.Add([pscustomobject]@{ Source = $statisticsPath; FileName = $fileName; Artifact = $statisticsArtifact })
        $row.HtmlStatistics = [pscustomobject][ordered]@{
            SampleCount = $statistics.'product-detail'.sampleCount
            ErrorCount = $statistics.'product-detail'.errorCount
            AverageMs = $statistics.'product-detail'.meanResTime
            ThroughputPerSecond = $statistics.'product-detail'.throughput
            Source = $statisticsArtifact; Export = ("statistics/$fileName")
        }
        $measurements.Add($row)
    } else { $warmups.Add($row) }
}
if ($measurements.Count -ne 8 -or $warmups.Count -ne 8 -or $rawJtls.Count -ne 16 -or $rawStatistics.Count -ne 8) {
    throw 'Expected eight main runs, eight warm-ups, sixteen JTLs, and eight statistics files.'
}
if ([string]::IsNullOrWhiteSpace($OutputDirectory)) {
    $OutputDirectory = Join-Path $repositoryRoot 'docs\week4\r02-product-detail-local-cache\baseline\jmeter'
} elseif (-not [IO.Path]::IsPathRooted($OutputDirectory)) {
    $OutputDirectory = Join-Path $repositoryRoot $OutputDirectory
}
$OutputDirectory = [IO.Path]::GetFullPath($OutputDirectory)
[void](Get-SummaryRelativePath (Join-Path $OutputDirectory 'summary.json'))
if ($OutputDirectory.StartsWith($rawPrefix, [StringComparison]::OrdinalIgnoreCase)) {
    throw 'Do not export over the raw benchmark source tree.'
}
$summaryPath = Join-Path $OutputDirectory 'summary.json'
$statisticsDirectory = Join-Path $OutputDirectory 'statistics'
if (Test-Path -LiteralPath $summaryPath) { throw "Summary already exists; choose a fresh output directory: $summaryPath" }
foreach ($copy in $statisticsCopies) {
    if (Test-Path -LiteralPath (Join-Path $statisticsDirectory $copy.FileName)) { throw 'A target statistics file already exists.' }
}
# Recheck source hashes before any export. No source file, API, or DB is changed.
foreach ($artifact in @($rawJtls | ForEach-Object {$_}) + @($rawStatistics | ForEach-Object {$_}) +
    @($rawMetrics | ForEach-Object {$_}) + @($inputArtifacts | ForEach-Object {$_}) +
    @($manifestArtifact, $jarArtifact, $jmeterJarArtifact)) {
    $path = Join-Path $repositoryRoot ($artifact.Path.Replace('/', [IO.Path]::DirectorySeparatorChar))
    if ((Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash -cne $artifact.Sha256) { throw 'Source changed while summarizing.' }
}
$javaVersionMatch = [regex]::Match($manifest.JavaVersion, 'version "(?<version>21\.[^"]+)"')
if (-not $javaVersionMatch.Success -or $manifest.JMeterVersion -cne '5.6.3') { throw 'Tool versions differ.' }
$summary = [pscustomobject][ordered]@{
    FormatVersion = 1; Status = 'Verified'; Purpose = 'week4-r02-product-detail-cache-results'
    GeneratedAtUtc = [DateTime]::UtcNow.ToString('o')
    StartedAtUtc = $manifest.StartedAtUtc; FinishedAtUtc = $manifest.FinishedAtUtc
    CodeGitSha = $manifest.CodeGitSha; SourceManifest = $manifestArtifact; ApplicationJar = $jarArtifact
    JMeterJar = $jmeterJarArtifact
    JavaVersion = $javaVersionMatch.Groups['version'].Value; JMeterVersion = $manifest.JMeterVersion
    PercentileMethod = 'raw JTL elapsed milliseconds; nearest-rank ceil(N*p), with zero mean allowed'
    HtmlValidation = 'sample/error counts, average, and throughput only; percentiles may use interpolation'
    Configuration = [ordered]@{
        WorkloadIds = $ids; Users = @(1,10); Repetitions = 2; WarmupSeconds = 20; MeasurementSeconds = 60
        RampupSeconds = 5; ConnectTimeoutMs = 3000; ResponseTimeoutMs = 30000
        CacheMaximumSize = 1000; CacheTtl = '30s'; ApiPort = 18081; ManagementPort = 18082; Validation = 'light'
        MeasurementOrder = $expectedOrder
    }
    Database = [ordered]@{
        Schema = $dbBefore.DatabaseSchema; Version = $dbBefore.MySqlVersion
        Products = $dbBefore.Products; ActiveProducts = $dbBefore.ActiveProducts; Likes = $dbBefore.Likes
        StoredLikes = $dbBefore.StoredLikes; PriceSum = $dbBefore.PriceSum
        ProductIndexes = $dbBefore.ProductIndexes; Settings = $dbBefore.Settings; BeforeAfterUnchanged = $true
    }
    Checks = [ordered]@{
        MainRuns = 8; Warmups = 8; SuccessfulStages = 16; FailedSamples = 0; DistinctIdsEachStage = 100
        StoppedApiProcesses = 4; HtmlReports = 8; FrozenInputs = 6
        ApplicationJarUnchanged = $true; ProtectedListenerBytesUnchanged = $true; ProtectedPorts = @(18080,8081)
        OffDbLoadsEqualRequests = $true; OnGetsEqualRequests = $true; OnDbLoadsEqualMisses = $true
        RawJtlFiles = 16; RawStatisticsFiles = 8; BytePreservedStatisticsCopies = 8
    }
    Inputs = @($inputArtifacts | ForEach-Object {$_})
    Measurements = @($measurements | ForEach-Object {$_})
    Warmups = @($warmups | ForEach-Object {$_})
    Sources = [ordered]@{
        Jtl = @($rawJtls | ForEach-Object {$_})
        Statistics = @($rawStatistics | ForEach-Object {$_})
        Metrics = @($rawMetrics | ForEach-Object {$_})
        ProtectedListeners = @($protectedBefore, $protectedAfter)
    }
    Summarizer = (Get-SummaryArtifact $PSCommandPath)
}
$json = ConvertTo-Json -InputObject $summary -Depth 20
if ($json -match '(?i)[A-Z]:\\\\' -or $json -match '(?i)\bCo(?:mmandLine|mpleteArgs)\b|InheritedJavaEnvironment') {
    throw 'Refusing to export private absolute paths or process/environment metadata.'
}
if (-not (Test-Path -LiteralPath $statisticsDirectory)) { [void](New-Item -ItemType Directory -Path $statisticsDirectory -Force) }
foreach ($copy in $statisticsCopies) {
    $destination = Join-Path $statisticsDirectory $copy.FileName
    Copy-Item -LiteralPath $copy.Source -Destination $destination -ErrorAction Stop
    if ((Get-FileHash -LiteralPath $destination -Algorithm SHA256).Hash -cne $copy.Artifact.Sha256) {
        throw 'Byte-preserving statistics copy verification failed.'
    }
}
$json | Set-Content -LiteralPath $summaryPath -Encoding UTF8
Write-Host ("Verified summary: " + (Get-SummaryRelativePath $summaryPath))
$summary
