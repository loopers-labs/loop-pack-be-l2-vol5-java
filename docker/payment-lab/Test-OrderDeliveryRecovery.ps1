param(
    [Parameter(Mandatory)][string]$W6Jar,
    [Parameter(Mandatory)][string]$CommerceJar,
    [Parameter(Mandatory)][string]$StreamerJar,
    [Parameter(Mandatory)][string]$PgJar
)

$ErrorActionPreference = 'Stop'
$W6Jar = (Resolve-Path -LiteralPath $W6Jar).Path
$CommerceJar = (Resolve-Path -LiteralPath $CommerceJar).Path
$StreamerJar = (Resolve-Path -LiteralPath $StreamerJar).Path
$PgJar = (Resolve-Path -LiteralPath $PgJar).Path
$settings = & java -XshowSettings:properties -version 2>&1 | Out-String
$javaHome = [regex]::Match($settings, '(?m)^\s*java\.home\s*=\s*(.+)$').Groups[1].Value.Trim()
if (!$javaHome) { throw 'Java installation could not be resolved.' }
$javaExe = (Resolve-Path -LiteralPath (Join-Path $javaHome 'bin/java.exe')).Path
$id = [Guid]::NewGuid().ToString('N').Substring(0,10)
$run = (New-Item -ItemType Directory -Force (Join-Path $PSScriptRoot "../../build/r03-lab/$id")).FullName
$mysql = "loopers-r03-mysql-$id"
$kafka = "loopers-r03-kafka-$id"
$topic = "order-events-$id"
$group = "purchase-$id"
$processes = [Collections.Generic.List[Diagnostics.Process]]::new()
$containers = [Collections.Generic.List[string]]::new()
Write-Host "RUN=$run"

function FreePort {
    $socket = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback, 0)
    $socket.Start()
    try { $socket.LocalEndpoint.Port } finally { $socket.Stop() }
}

function Invoke-LabDocker {
    $result = & docker.exe @args
    if ($LASTEXITCODE -ne 0) { throw "Docker command failed: $($args[0])" }
    $result
}

function Sql([string]$statement) {
    Invoke-LabDocker exec -e MYSQL_PWD=loopers-lab $mysql mysql -uroot --batch --skip-column-names --default-character-set=utf8mb4 -e $statement
}

function ApplySql([IO.FileInfo]$file) {
    Get-Content -LiteralPath $file.FullName -Encoding utf8 -Raw |
        docker.exe exec -i -e MYSQL_PWD=loopers-lab $mysql mysql -uroot --default-character-set=utf8mb4
    if ($LASTEXITCODE -ne 0) { throw "SQL failed: $($file.Name)" }
}

function Until([string]$label, [scriptblock]$condition, [int]$seconds = 50) {
    $deadline = [DateTime]::UtcNow.AddSeconds($seconds)
    do {
        if (& $condition) { return }
        Start-Sleep -Milliseconds 350
    } while ([DateTime]::UtcNow -lt $deadline)
    throw "Timed out: $label. Logs: $run"
}

function Launch([string]$name, [string[]]$arguments) {
    $quoted = ((@('-XX:ActiveProcessorCount=4', '-Xmx512m') + $arguments) | ForEach-Object { '"' + $_ + '"' }) -join ' '
    $process = Start-Process $javaExe -ArgumentList $quoted -WorkingDirectory $run -WindowStyle Hidden -PassThru -RedirectStandardOutput "$run/$name.log" -RedirectStandardError "$run/$name.err"
    $processes.Add($process)
    $process
}

function StopLabProcess($process) {
    if (!$process.HasExited) {
        $process.Kill($true)
        if (!$process.WaitForExit(30000)) { throw "Lab JVM did not stop: $($process.Id)" }
    }
}

function SeeLog([string]$name, [string]$pattern, $process) {
    Until "$name $pattern" {
        if ($process.HasExited -and $process.ExitCode -ne 0) { throw "Lab JVM failed: $name" }
        (Test-Path "$run/$name.log") -and [bool](Select-String -Path "$run/$name.log" -Pattern $pattern -Quiet)
    } 120
}

function AssertEqual($actual, $expected, [string]$label) {
    if (($actual -join ';') -cne ($expected -join ';')) { throw "$label expected [$expected], got [$actual]" }
}

function AccessCount([string]$folder, [string]$pattern) {
    $files = @(Get-ChildItem "$run/$folder/logs" -Filter 'access_log.*' -ErrorAction SilentlyContinue)
    if ($files.Count -eq 0) { return 0 }
    @(Select-String -Path $files.FullName -Pattern $pattern).Count
}

function StartApi([string]$name, [string]$jar, [int]$debugPort = 0) {
    $debug = @()
    if ($debugPort -gt 0) {
        $debug = @("-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=127.0.0.1:$debugPort")
    }
    $process = Launch $name ($debug + @('-jar', $jar, '--spring.profiles.active=payment-lab',
        '--server.port=0', '--management.server.port=-1',
        "--datasource.mysql-jpa.main.jdbc-url=jdbc:mysql://127.0.0.1:$dbPort/loopers",
        '--datasource.mysql-jpa.main.minimum-idle=2',
        '--datasource.mysql-jpa.main.connection-timeout=10000', '--datasource.mysql-jpa.main.initialization-fail-timeout=30000',
        "--spring.kafka.bootstrap-servers=127.0.0.1:$kafkaPort", "--order-events.topic=$topic",
        "--pg.client.base-url=http://127.0.0.1:$pgPort",
        "--pg.client.callback-url=http://127.0.0.1:$callbackPort/api/v1/payments/callback",
        '--payment.recovery.auto-start-enabled=false', '--scheduler.like-count.enabled=false',
        '--server.tomcat.accesslog.enabled=true', '--server.tomcat.accesslog.buffered=false',
        "--server.tomcat.basedir=$run/$name"))
    SeeLog $name 'Started CommerceApiApplication' $process
    $portLine = Select-String -Path "$run/$name.log" -Pattern 'Tomcat started on port (\d+)' | Select-Object -First 1
    if (!$portLine) { throw "No HTTP port found for $name" }
    $script:apiPort = [int]$portLine.Matches[0].Groups[1].Value
    $process
}

function Hold([string]$name, [int]$port, [string]$type, [string]$method) {
    $process = Launch $name @('--add-modules', 'jdk.jdi', "$PSScriptRoot/HoldMethodEntry.java", "$port", $type, $method)
    SeeLog $name 'BREAKPOINT_READY' $process
    $process
}

function CreateOrder {
    $request = '{"items":[{"productId":7,"quantity":2},{"productId":8,"quantity":1}]}'
    $response = Invoke-RestMethod -Uri "http://127.0.0.1:$apiPort/api/v1/orders" -Method Post -Headers @{'X-USER-ID'='1'} -Body $request -ContentType 'application/json' -TimeoutSec 15
    [long]$response.data.orderId
}

function ConfirmBody {
    @{
        paymentAttemptKey = [Guid]::NewGuid().ToString()
        cardType = 'TEST'
        cardNo = '0000-0000-0000-0000'
        testOptions = @{ preset = 'NORMAL_SUCCESS'; overrides = @{ callbackMode = 'DROP' } }
    } | ConvertTo-Json -Depth 4 -Compress
}

function PreparePgSuccess([long]$orderId) {
    $response = Invoke-RestMethod -Uri "http://127.0.0.1:$apiPort/api/v1/orders/$orderId/confirm" -Method Post -Headers @{'X-USER-ID'='1'} -Body (ConfirmBody) -ContentType 'application/json' -TimeoutSec 20
    AssertEqual $response.data.status 'PAYMENT_PENDING' "order $orderId accepted by PG"
    Until "PG success for order $orderId" {
        (Sql "SELECT t.status FROM loopers_test_pg.pg_transactions t JOIN loopers.payment_attempts p ON t.provider_order_id=p.provider_order_id WHERE p.order_id=$orderId") -eq 'SUCCESS'
    }
}

function Confirm([long]$orderId) {
    PreparePgSuccess $orderId
    $response = Invoke-RestMethod -Uri "http://127.0.0.1:$apiPort/api/v1/orders/$orderId/payment/reconcile" -Method Post -Headers @{'X-USER-ID'='1'} -TimeoutSec 20
    AssertEqual $response.data.status 'CONFIRMED' "order $orderId confirmation"
}

function OrderFacts([long]$orderId) {
    Sql "SELECT CONCAT_WS('|',o.id,o.status,r.payment_method,r.status,r.confirmation_event_id,DATE_FORMAT(r.created_at,'%Y-%m-%dT%H:%i:%s.%fZ'),p.provider_order_id,p.transaction_key,p.outcome,s.state,s.version) FROM loopers.orders o JOIN loopers.order_records r ON r.order_id=o.id JOIN loopers.payment_attempts p ON p.order_id=o.id JOIN loopers.stock_adjustments s ON s.order_id=o.id WHERE o.id=$orderId"
    Sql "SELECT CONCAT_WS('|',product_id,quantity) FROM loopers.order_items WHERE order_id=$orderId ORDER BY product_id"
}

function PurchaseFacts([long]$orderId) {
    Sql "SELECT CONCAT_WS('|',id,event_id,confirmed_at,processed_at,normalized_payload) FROM loopers.order_confirmation_inbox WHERE order_id=$orderId"
    Sql "SELECT CONCAT_WS('|',id,inbox_id,product_id,quantity,confirmed_at) FROM loopers.product_purchase_activities WHERE order_id=$orderId ORDER BY product_id"
}

function AssertPurchase([long]$orderId, [string]$eventId) {
    AssertEqual (Sql "SELECT event_id FROM loopers.order_confirmation_inbox WHERE order_id=$orderId") $eventId "order $orderId Inbox"
    AssertEqual (Sql "SELECT CONCAT(product_id,':',quantity) FROM loopers.product_purchase_activities WHERE order_id=$orderId ORDER BY product_id") @('7:2','8:1') "order $orderId activity"
}

function PgFacts {
    Sql "SELECT CONCAT_WS('|',provider_order_id,transaction_key,status) FROM loopers_test_pg.pg_transactions ORDER BY provider_order_id"
}

function BrokerCount {
    $offsets = @(Invoke-LabDocker exec $kafka /opt/kafka/bin/kafka-get-offsets.sh --bootstrap-server 127.0.0.1:9093 --topic $topic)
    AssertEqual $offsets.Count 2 'topic partitions'
    ($offsets | ForEach-Object { [long](($_ -split ':')[-1]) } | Measure-Object -Sum).Sum
}

function Recover([string]$name, [long]$orderId, [string]$eventId, [string]$result) {
    $process = Launch $name @('-jar', $CommerceJar, '--spring.profiles.active=payment-lab,outbox-recover',
        "--datasource.mysql-jpa.main.jdbc-url=jdbc:mysql://127.0.0.1:$dbPort/loopers",
        '--datasource.mysql-jpa.main.minimum-idle=2',
        '--datasource.mysql-jpa.main.connection-timeout=10000', '--datasource.mysql-jpa.main.initialization-fail-timeout=30000',
        "--order-events.topic=$topic", "--order-events.recovery.order-id=$orderId", "--order-events.recovery.event-id=$eventId")
    SeeLog $name "Outbox recovery orderId=$orderId eventId=$eventId result=$result" $process
    if (!$process.WaitForExit(15000) -or $process.ExitCode -ne 0) { throw "Recovery did not exit: $name" }
}

try {
    $dbPort, $kafkaPort, $callbackPort, $pgPort, $streamerPort, $debugA, $debugC = 1..7 | ForEach-Object { FreePort }
    AssertEqual (@($dbPort,$kafkaPort,$callbackPort,$pgPort,$streamerPort,$debugA,$debugC | Select-Object -Unique).Count) 7 'unique ports'
    Invoke-LabDocker run -d --name $mysql -p "127.0.0.1:$($dbPort):3306" -e MYSQL_ROOT_PASSWORD=loopers-lab mysql:8.0 | Out-Null
    $containers.Add($mysql)
    Until 'MySQL TCP' {
        $ping = & docker.exe exec $mysql mysqladmin ping --host=127.0.0.1 --user=root --password=loopers-lab --silent 2>&1
        $ready = $LASTEXITCODE -eq 0
        $ping | Set-Content -LiteralPath "$run/mysql-readiness.log" -Encoding utf8
        $ready
    } 90
    Get-ChildItem -LiteralPath $PSScriptRoot -Filter '*.sql' | Where-Object Name -Match '^0(0[0-9]|10)-' |
        Sort-Object Name | ForEach-Object { ApplySql $_ }
    Sql @'
USE loopers;
INSERT INTO users (id) VALUES (1);
INSERT INTO brands (id, deleted, created_at, updated_at, name) VALUES (1, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), 'R03 Lab');
INSERT INTO products (id, deleted, stock, brand_id, created_at, updated_at, price, name)
VALUES (7, 0, 20, 1, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), 1000, 'R03 Product 7'),
       (8, 0, 20, 1, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), 2000, 'R03 Product 8');
'@
    $kafkaArgs = @('run', '-d', '--name', $kafka, '-p', "127.0.0.1:$($kafkaPort):9092",
        '-e', 'KAFKA_NODE_ID=1', '-e', 'KAFKA_PROCESS_ROLES=broker,controller',
        '-e', 'KAFKA_LISTENERS=PLAINTEXT://:9092,BROKER://:9093,CONTROLLER://:9094',
        '-e', "KAFKA_ADVERTISED_LISTENERS=PLAINTEXT://127.0.0.1:$kafkaPort,BROKER://127.0.0.1:9093",
        '-e', 'KAFKA_LISTENER_SECURITY_PROTOCOL_MAP=PLAINTEXT:PLAINTEXT,BROKER:PLAINTEXT,CONTROLLER:PLAINTEXT',
        '-e', 'KAFKA_CONTROLLER_LISTENER_NAMES=CONTROLLER', '-e', 'KAFKA_INTER_BROKER_LISTENER_NAME=BROKER',
        '-e', 'KAFKA_CONTROLLER_QUORUM_VOTERS=1@127.0.0.1:9094', '-e', 'KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR=1',
        '-e', 'KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR=1', '-e', 'KAFKA_TRANSACTION_STATE_LOG_MIN_ISR=1',
        '-e', 'KAFKA_GROUP_INITIAL_REBALANCE_DELAY_MS=0', '-e', 'KAFKA_AUTO_CREATE_TOPICS_ENABLE=false', 'apache/kafka:3.8.0')
    Invoke-LabDocker @kafkaArgs | Out-Null
    $containers.Add($kafka)
    Until 'Kafka ready' { [bool](& docker.exe logs $kafka 2>&1 | Select-String 'Kafka Server started') }
    Invoke-LabDocker exec $kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server 127.0.0.1:9093 --create --topic $topic --partitions 2 --replication-factor 1 | Out-Null

    $pg = Launch 'pg' @('-jar', $PgJar, "--server.port=$pgPort",
        "--datasource.mysql-jpa.main.jdbc-url=jdbc:mysql://127.0.0.1:$dbPort/loopers_test_pg",
        '--datasource.mysql-jpa.main.connection-timeout=10000', '--datasource.mysql-jpa.main.initialization-fail-timeout=30000',
        "--pg.callback-url=http://127.0.0.1:$callbackPort/api/v1/payments/callback",
        '--server.tomcat.accesslog.enabled=true', '--server.tomcat.accesslog.buffered=false', "--server.tomcat.basedir=$run/pg")
    SeeLog 'pg' 'Started TestPgApplication' $pg
    $streamer = Launch 'streamer' @('-jar', $StreamerJar, '--spring.profiles.active=local', '--spring.jpa.hibernate.ddl-auto=none',
        "--server.port=$streamerPort", "--management.server.port=$streamerPort",
        "--datasource.mysql-jpa.main.jdbc-url=jdbc:mysql://127.0.0.1:$dbPort/loopers",
        '--datasource.mysql-jpa.main.minimum-idle=2',
        '--datasource.mysql-jpa.main.connection-timeout=10000', '--datasource.mysql-jpa.main.initialization-fail-timeout=30000',
        "--spring.kafka.bootstrap-servers=127.0.0.1:$kafkaPort", "--order-events.topic=$topic", "--order-events.group=$group")
    SeeLog 'streamer' 'partitions assigned' $streamer

    $api = StartApi 'w6-a' $W6Jar $debugA
    $probe = Hold 'hold-w6' $debugA 'com.loopers.infrastructure.messaging.ordering.OrderConfirmedPublicationListener' 'publish'
    $a = CreateOrder
    PreparePgSuccess $a
    $client = [Net.Http.HttpClient]::new()
    try {
        $client.DefaultRequestHeaders.Add('X-USER-ID', '1')
        $call = $client.PostAsync("http://127.0.0.1:$apiPort/api/v1/orders/$a/payment/reconcile", $null)
        Until 'W6 direct send boundary' {
            if (Select-String -Path "$run/hold-w6.log" -Pattern 'BREAKPOINT_HIT' -Quiet) { return $true }
            if ($call.IsCompleted) {
                $response = $call.GetAwaiter().GetResult()
                throw "A reconcile returned HTTP $([int]$response.StatusCode) before the direct send boundary"
            }
            $false
        } 90
        $aBefore = @(OrderFacts $a)
        if ($aBefore[0] -notmatch '\|CONFIRMED\|PG\|PAID\|.+\|SUCCESS\|CONSUMED\|') { throw 'A was not committed' }
        AssertEqual @(PurchaseFacts $a).Count 0 'A before recovery'
        StopLabProcess $api
    } finally { $client.Dispose() }
    StopLabProcess $probe
    $eventA = ($aBefore[0] -split '\|')[4]

    $api = StartApi 'w6-b' $W6Jar
    $b = CreateOrder
    Confirm $b
    $bBefore = @(OrderFacts $b)
    $eventB = ($bBefore[0] -split '\|')[4]
    Until 'B Inbox' { (Sql "SELECT COUNT(*) FROM loopers.order_confirmation_inbox WHERE order_id=$b") -eq '1' }
    AssertPurchase $b $eventB
    AssertEqual @(PurchaseFacts $a).Count 0 'A remains missing after B'
    $bPurchaseBefore = @(PurchaseFacts $b)
    $pgBefore = @(PgFacts)
    $stockBefore = @(Sql "SELECT CONCAT(id,':',stock) FROM loopers.products ORDER BY id")
    AssertEqual $pgBefore.Count 2 'W6 PG transactions'
    AssertEqual (AccessCount 'pg' 'POST /api/v1/payments ') 2 'W6 PG creates'
    $pgLookupBefore = AccessCount 'pg' 'GET /api/v1/payments[/ ?]'
    $callbackBefore = (AccessCount 'w6-a' 'POST /api/v1/payments/callback ') + (AccessCount 'w6-b' 'POST /api/v1/payments/callback ')
    AssertEqual (Sql "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='loopers' AND table_name='order_event_outbox'") 0 'W6 Outbox absent'
    AssertEqual (BrokerCount) 1 'only B published in W6'
    StopLabProcess $api
    Write-Host "W6_READY A=$a/$eventA B=$b/$eventB"

    ApplySql (Get-Item -LiteralPath "$PSScriptRoot/011-order-event-outbox.sql")
    Recover 'recover-create' $a $eventA 'CREATED'
    AssertEqual (Sql "SELECT CONCAT(status,':',attempts) FROM loopers.order_event_outbox WHERE order_id=$a") 'PENDING:0' 'A staged'
    $api = StartApi 'w7-first' $CommerceJar $debugC
    Until 'A published and consumed' {
        ((Sql "SELECT status FROM loopers.order_event_outbox WHERE order_id=$a") -eq 'PUBLISHED') -and
        ((Sql "SELECT COUNT(*) FROM loopers.order_confirmation_inbox WHERE order_id=$a") -eq '1')
    }
    $aPurchase = @(PurchaseFacts $a)
    $aOutboxSql = "SELECT CONCAT_WS('|',status,attempts,published_at,published_partition,published_offset,SHA2(payload,256)) FROM loopers.order_event_outbox WHERE order_id=$a"
    $aOutbox = @(Sql $aOutboxSql)
    Recover 'recover-repeat' $a $eventA 'ALREADY_PRESENT'
    AssertEqual @(PurchaseFacts $a) $aPurchase 'A after repeated recovery'
    AssertEqual @(Sql $aOutboxSql) $aOutbox 'A published row unchanged'
    AssertEqual @(OrderFacts $a) $aBefore 'A confirmation and reservation unchanged'
    AssertEqual @(OrderFacts $b) $bBefore 'B confirmation and reservation unchanged'
    AssertEqual @(PurchaseFacts $b) $bPurchaseBefore 'B purchase unchanged'
    AssertEqual (Sql "SELECT COUNT(*) FROM loopers.order_event_outbox WHERE order_id=$b") 0 'B Outbox absent'
    AssertEqual @(PgFacts) $pgBefore 'no new PG transaction on recovery'
    AssertEqual (AccessCount 'pg' 'POST /api/v1/payments ') 2 'no PG create on recovery'
    AssertEqual (AccessCount 'pg' 'GET /api/v1/payments[/ ?]') $pgLookupBefore 'no PG lookup on recovery'
    AssertEqual ((AccessCount 'w6-a' 'POST /api/v1/payments/callback ') + (AccessCount 'w6-b' 'POST /api/v1/payments/callback ') + (AccessCount 'w7-first' 'POST /api/v1/payments/callback ')) $callbackBefore 'no PG callback on recovery'
    AssertEqual @(Sql "SELECT CONCAT(id,':',stock) FROM loopers.products ORDER BY id") $stockBefore 'stock unchanged on recovery'
    AssertPurchase $a $eventA
    Write-Host 'RECOVERY_OK'

    # The recovery above targets A alone. Replay B explicitly to verify the already-consumed path.
    Recover 'replay-b' $b $eventB 'CREATED'
    SeeLog 'streamer' "eventId=$eventB .*result=ALREADY_PROCESSED" $streamer
    Until 'B replay published' { (Sql "SELECT status FROM loopers.order_event_outbox WHERE order_id=$b") -eq 'PUBLISHED' }
    Recover 'replay-b-repeat' $b $eventB 'ALREADY_PRESENT'
    AssertEqual @(PurchaseFacts $b) $bPurchaseBefore 'B purchase unchanged after explicit replay'
    AssertEqual @(OrderFacts $b) $bBefore 'B confirmation and reservation unchanged after explicit replay'
    AssertEqual @(PgFacts) $pgBefore 'B replay did not create a PG transaction'
    AssertEqual (AccessCount 'pg' 'POST /api/v1/payments ') 2 'B replay did not call PG create'
    AssertEqual (AccessCount 'pg' 'GET /api/v1/payments[/ ?]') $pgLookupBefore 'B replay did not call PG lookup'
    AssertEqual ((AccessCount 'w6-a' 'POST /api/v1/payments/callback ') + (AccessCount 'w6-b' 'POST /api/v1/payments/callback ') + (AccessCount 'w7-first' 'POST /api/v1/payments/callback ')) $callbackBefore 'B replay did not call callback'

    $probe = Hold 'hold-w7' $debugC 'com.loopers.infrastructure.persistence.ordering.OrderOutboxJpaRepository' 'publish'
    $c = CreateOrder
    Confirm $c
    $pgLookupBeforeRestart = AccessCount 'pg' 'GET /api/v1/payments[/ ?]'
    SeeLog 'hold-w7' 'BREAKPOINT_HIT' $probe
    $cBefore = @(OrderFacts $c)
    $eventC = ($cBefore[0] -split '\|')[4]
    Until 'C first Inbox' { (Sql "SELECT COUNT(*) FROM loopers.order_confirmation_inbox WHERE order_id=$c") -eq '1' }
    AssertEqual (Sql "SELECT CONCAT(status,':',attempts) FROM loopers.order_event_outbox WHERE order_id=$c") 'PENDING:1' 'C after first broker ack'
    $cPurchase = @(PurchaseFacts $c)
    StopLabProcess $api
    StopLabProcess $probe

    $api = StartApi 'w7-restarted' $CommerceJar
    Until 'C published on restart' {
        (Sql "SELECT CONCAT(status,':',attempts) FROM loopers.order_event_outbox WHERE order_id=$c") -eq 'PUBLISHED:2'
    }
    SeeLog 'streamer' "eventId=$eventC .*result=ALREADY_PROCESSED" $streamer
    AssertEqual @(OrderFacts $c) $cBefore 'C confirmation and reservation unchanged'
    AssertEqual @(PurchaseFacts $c) $cPurchase 'C purchase unchanged after redelivery'
    AssertPurchase $c $eventC
    AssertEqual (Sql 'SELECT COUNT(*) FROM loopers.order_event_outbox') 3 'A, B and C Outbox'
    AssertEqual (Sql 'SELECT COUNT(*) FROM loopers.point_bills') 0 'no PG point debit'
    AssertEqual (AccessCount 'pg' 'POST /api/v1/payments ') 3 'one C PG create'
    AssertEqual @(PgFacts).Count 3 'one C PG transaction'
    AssertEqual (AccessCount 'pg' 'GET /api/v1/payments[/ ?]') $pgLookupBeforeRestart 'no PG lookup on restart'
    AssertEqual @(Sql "SELECT CONCAT(id,':',stock) FROM loopers.products ORDER BY id") @('7:14','8:17') 'exactly three stock reservations'

    $readArgs = @('exec', $kafka, '/opt/kafka/bin/kafka-console-consumer.sh', '--bootstrap-server', '127.0.0.1:9093', '--topic', $topic,
        '--from-beginning', '--max-messages', '5', '--timeout-ms', '15000', '--property', 'print.partition=true',
        '--property', 'print.offset=true', '--property', 'print.key=true')
    AssertEqual (BrokerCount) 5 'total Kafka records'
    $records = @(Invoke-LabDocker @readArgs | Where-Object { $_ -match '\{' } | ForEach-Object {
        $jsonStart = $_.IndexOf('{')
        $raw = $_.Substring($jsonStart)
        $data = $raw | ConvertFrom-Json
        $fields = $_.Substring(0, $jsonStart).Trim() -split '\s+'
        AssertEqual $fields[-1] "$($data.orderId)" 'broker key'
        [ordered]@{eventId=$data.eventId; orderId=$data.orderId; partition=$fields[0]; offset=$fields[1]; key=$fields[-1]; payload=$raw}
    })
    AssertEqual @($records | Where-Object { $_.eventId -eq $eventA }).Count 1 'A Kafka records'
    AssertEqual @($records | Where-Object { $_.eventId -eq $eventB }).Count 2 'B Kafka records'
    $cRecords = @($records | Where-Object { $_.eventId -eq $eventC })
    AssertEqual $cRecords.Count 2 'C Kafka records'
    AssertEqual $cRecords[0].payload $cRecords[1].payload 'C Kafka payload'
    AssertEqual $cRecords[0].partition $cRecords[1].partition 'C Kafka partition'
    if ($cRecords[0].offset -eq $cRecords[1].offset) { throw 'C records must have different offsets' }
    $offsets = @(Invoke-LabDocker exec $kafka /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server 127.0.0.1:9093 --group $group --describe)
    $groupRows = @($offsets | Where-Object { $_ -match "^\s*$group\s+$topic\s+" })
    AssertEqual $groupRows.Count 2 'consumer group partitions'
    foreach ($line in $groupRows) {
        $fields = $line.Trim() -split '\s+'
        if ($fields[4] -ne '0') { AssertEqual $fields[5] '0' 'consumer lag' }
    }
    [ordered]@{
        runId=$id; topic=$topic; group=$group; w6Revision='42d8ed8'
        orders=[ordered]@{A=$aBefore; B=$bBefore; C=$cBefore}
        recovery=[ordered]@{pgCreatesBefore=2; pgCreatesAfter=2; pgTransactions=$pgBefore; lookupDelta=0; callbackDelta=0; stock=$stockBefore; outbox=$aOutbox}
        purchases=[ordered]@{A=$aPurchase; B=$bPurchaseBefore; C=$cPurchase}
        brokerRecords=$records; consumerGroup=$offsets
    } | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath "$run/evidence.json" -Encoding utf8
    Write-Host "DELIVERY_RECOVERY_OK $run/evidence.json"
} catch {
    Write-Warning "Lab failed: $($_.Exception.Message)"
    throw
} finally {
    foreach ($process in $processes) {
        try { StopLabProcess $process } catch { Write-Warning $_.Exception.Message }
    }
    foreach ($container in $containers) {
        & docker.exe logs $container *> "$run/$container.log"
        & docker.exe rm -f $container | Out-Null
    }
}
