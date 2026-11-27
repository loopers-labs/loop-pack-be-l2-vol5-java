[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$RunDir,
    [ValidateRange(1, 65535)][int]$ApiPort = 18280,
    [ValidateRange(1, 1000000)][int]$UserId = 1001,
    [ValidateRange(1, 1000000)][int]$FixtureUserId = 1002,
    [ValidateRange(1, 1000000)][int]$WaterId = 3,
    [ValidateRange(1, 1000000)][int]$CoffeeId = 1,
    [ValidateRange(1, 1000000)][int]$TeaId = 2,
    [ValidateRange(1, 1000)][int]$Readers = 1000,
    [ValidateRange(1, 120)][int]$RampSeconds = 120,
    [ValidateRange(5, 1800)][int]$DurationSeconds = 840,
    [switch]$Incident,
    [string]$CatalogFile,
    [ValidateRange(0, 100)][double]$CheckoutPercent = 1.6,
    [string]$JavaHome = 'C:\Program Files\Java\jdk-21.0.10'
)
$ErrorActionPreference = 'Stop'
if ($Incident -and !$PSBoundParameters.ContainsKey('ApiPort')) { $ApiPort = 18380 }
if (!$CatalogFile) { $CatalogFile = Join-Path $PSScriptRoot '..\stack\catalog\catalog.csv' }
if ($DurationSeconds -le $RampSeconds) { throw 'The hold interval must follow ramp-up.' }
if (!(Test-Path -LiteralPath $RunDir -PathType Container)) {
    [void][IO.Directory]::CreateDirectory([IO.Path]::GetFullPath($RunDir))
}
$run = (Resolve-Path -LiteralPath $RunDir).Path
foreach ($name in 'jmeter.pid','samples.jtl','jmeter.log','telemetry.csv','order-requests.csv','stop-load') {
    if (Test-Path -LiteralPath (Join-Path $run $name)) { throw "Existing run artifact: $name" }
}
$repo = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..\..\..\..')).Path
$jar = (Get-Item -LiteralPath (Join-Path $repo 'apache-jmeter-5.6.3\bin\ApacheJMeter.jar')).FullName
$java = (Get-Item -LiteralPath (Join-Path $JavaHome 'bin\java.exe')).FullName
$catalog = (Get-Item -LiteralPath $CatalogFile).FullName
$checkout = $CheckoutPercent.ToString([Globalization.CultureInfo]::InvariantCulture)
$arguments = @('-Xms192m', '-Xmx768m', '-Xss512k', '-Dfile.encoding=UTF-8', '-Djava.awt.headless=true',
    '-jar', $jar, '-n', '-t', (Join-Path $PSScriptRoot 'control.jmx'),
    '-l', (Join-Path $run 'samples.jtl'), '-j', (Join-Path $run 'jmeter.log'),
    '-q', (Join-Path $PSScriptRoot 'control.properties'),
    "-JrunDir=$run", "-Jscripts=$PSScriptRoot", "-Jport=$ApiPort",
    "-JwaterId=$WaterId", "-JcoffeeId=$CoffeeId", "-JteaId=$TeaId",
    "-JwaterUser=$UserId", "-JfixtureUser=$FixtureUserId",
    "-Jreaders=$Readers", "-Jramp=$RampSeconds", "-Jduration=$DurationSeconds",
    "-Jcatalog=$catalog", "-Jcheckout=$checkout", "-Jincident=$($Incident.IsPresent.ToString().ToLowerInvariant())")
# Start-Process takes one Windows argument string. All paths address files, not shell code.
$quoted = ($arguments | ForEach-Object { '"' + $_ + '"' }) -join ' '
$process = Start-Process -FilePath $java -ArgumentList $quoted -WorkingDirectory $PSScriptRoot -PassThru -WindowStyle Hidden `
    -RedirectStandardOutput (Join-Path $run 'jmeter.stdout.log') -RedirectStandardError (Join-Path $run 'jmeter.stderr.log')
[IO.File]::WriteAllText((Join-Path $run 'jmeter.pid'), [string]$process.Id)
$process
