[CmdletBinding()]
param(
    [string]$JavaHome = 'C:\Program Files\Java\jdk-21.0.10'
)

$ErrorActionPreference = 'Stop'
$repositoryRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..\..\..'))
$jmeterHome = Join-Path $repositoryRoot 'apache-jmeter-5.6.3'
$jmeterBatch = Join-Path $jmeterHome 'bin\jmeter.bat'
$javaExecutable = Join-Path $JavaHome 'bin\java.exe'
$planPath = Join-Path $PSScriptRoot 'product-list-ui.jmx'
$propertiesPath = Join-Path $PSScriptRoot 'results.properties'
$expectedPath = Join-Path $PSScriptRoot 'expected-responses.json'
foreach ($file in @($jmeterBatch, $javaExecutable, $planPath, $propertiesPath, $expectedPath)) {
    if (-not (Test-Path -LiteralPath $file -PathType Leaf)) {
        throw "필수 파일을 찾을 수 없습니다: $file"
    }
}

$sessionName = (Get-Date -Format 'yyyyMMdd-HHmmss-fffffff') + '-' + [guid]::NewGuid().ToString('N').Substring(0, 8)
$outputDirectory = Join-Path (Join-Path $repositoryRoot 'build\benchmark\jmeter-ui') $sessionName
[void](New-Item -ItemType Directory -Path $outputDirectory)
$warmupFile = Join-Path $outputDirectory 'warmup.jtl'
$resultFile = Join-Path $outputDirectory 'measurement.jtl'
$logFile = Join-Path $outputDirectory 'jmeter.log'
$arguments = @('-t', $planPath, '-q', $propertiesPath, '-j', $logFile,
    "-JguiWarmupFile=$warmupFile", "-JguiResultFile=$resultFile", "-JexpectedFile=$expectedPath")

function ConvertTo-NativeArgument {
    param([string]$Value)

    if ($Value.Length -gt 0 -and $Value -notmatch '[\s"]') {
        return $Value
    }
    $escaped = [regex]::Replace($Value, '(\\*)"', '$1$1\"')
    $escaped = [regex]::Replace($escaped, '(\\+)$', '$1$1')
    return '"' + $escaped + '"'
}

$nativeArguments = ($arguments | ForEach-Object { ConvertTo-NativeArgument $_ }) -join ' '
$commandLine = '/d /s /c ""' + $jmeterBatch + '" ' + $nativeArguments + '"'
$environmentNames = @('JAVA_HOME', 'PATH', 'JMETER_HOME', 'JMETER_BIN', 'JM_LAUNCH', 'JM_START')
$previousEnvironment = @{}
foreach ($name in $environmentNames) {
    $previousEnvironment[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
}
try {
    [Environment]::SetEnvironmentVariable('JAVA_HOME', $JavaHome, 'Process')
    [Environment]::SetEnvironmentVariable('PATH', (Join-Path $JavaHome 'bin') + ';' + $previousEnvironment['PATH'], 'Process')
    [Environment]::SetEnvironmentVariable('JMETER_HOME', $jmeterHome, 'Process')
    [Environment]::SetEnvironmentVariable('JMETER_BIN', (Join-Path $jmeterHome 'bin') + '\', 'Process')
    [Environment]::SetEnvironmentVariable('JM_LAUNCH', $javaExecutable, 'Process')
    [Environment]::SetEnvironmentVariable('JM_START', $null, 'Process')
    $process = Start-Process -FilePath $env:ComSpec -ArgumentList $commandLine -WorkingDirectory $repositoryRoot `
        -WindowStyle Hidden -PassThru
} finally {
    foreach ($name in $environmentNames) {
        [Environment]::SetEnvironmentVariable($name, $previousEnvironment[$name], 'Process')
    }
}
$session = [pscustomobject][ordered]@{
    Mode = 'GUI'
    AutomaticStart = $false
    LauncherProcessId = $process.Id
    OpenedAtUtc = [DateTime]::UtcNow.ToString('o')
    PlanPath = $planPath
    OutputDirectory = $outputDirectory
    WarmupFile = $warmupFile
    MeasurementFile = $resultFile
    LogFile = $logFile
}
$session | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $outputDirectory 'session.json') -Encoding UTF8
Write-Host 'JMeter GUI를 열었습니다. 초록 Start 버튼을 누르기 전에는 요청을 보내지 않습니다.'
Write-Host "결과 저장 경로: $outputDirectory"
$session
