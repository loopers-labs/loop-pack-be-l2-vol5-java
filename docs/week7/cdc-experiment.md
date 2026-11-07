# CDC 실험: Debezium 은 outbox 를 어떻게 순서대로, 빠짐없이 꺼내는가

W7 의 polling relay 는 outbox 를 행 단위로 꺼낸다. 이 문서에서는 같은 outbox 테이블을 binlog 로 꺼내는 Debezium 을 실제로 띄워 보고,
순서와 누락을 무엇으로 지키는지 소스와 실행 결과로 확인한다.
relay 를 CDC 로 바꿀지는 정하지 않았다. 바꾸면 무엇을 얻고 잃는지 판단할 근거를 모으는 문서다.

CDC 는 outbox 를 대신하지 않고 relay 를 대신한다. outbox 는 무엇을 보낼지(확정과 같은 TX 에 남는 사실)를 정하고,
relay 와 CDC 는 그것을 어떻게 꺼낼지를 맡는다.

## 1. 구성

| 구성요소 | 버전과 설정 | 출처 |
|---|---|---|
| MySQL | 8.0, `log_bin=ON`, `binlog_format=ROW`, `binlog_row_image=FULL`, `sync_binlog=1`, `server_id=1`, `gtid_mode=OFF`, `transaction_isolation=READ-COMMITTED` | `docker/infra-compose.yml` 그대로. 8.0 기본값이 CDC 에 필요한 조건을 이미 채운다 |
| Kafka | `bitnamilegacy/kafka:3.5.1` 단일 broker | `docker/infra-compose.yml` 그대로 |
| Kafka Connect | 4.3.0 (Debezium 이미지에 들어 있는 것) | `docker/cdc-compose.yml` |
| Debezium | MySQL 커넥터 3.6.3.Final | `quay.io/debezium/connect:3.6.3.Final` |
| 커넥터 | `order_event_outbox` 만 읽는다. outbox event router 로 `record_key` 를 key 로, `payload` 를 value 로, `topic` 컬럼에 `-cdc` 를 붙인 이름을 목적지 topic 으로, `event_id` 를 header `id` 로 보낸다 | `docker/cdc/outbox-connector.json` |

목적지를 `order-events-cdc` 로 따로 둔 것은 앱의 relay 가 같은 행을 `order-events` 로 보내기 때문이다. 두 경로가 섞이지 않아 나란히 비교할 수 있다.

```bash
docker compose -f docker/infra-compose.yml -f docker/cdc-compose.yml up -d mysql kafka kafka-init
docker exec -i docker-mysql-1 mysql -uroot -proot loopers < docker/cdc/outbox-table.sql   # 앱 없이 실험할 때
docker compose -f docker/infra-compose.yml -f docker/cdc-compose.yml up -d connect
curl -X POST localhost:8083/connectors -H 'Content-Type: application/json' -d @docker/cdc/outbox-connector.json
```

`cdc-compose.yml` 은 컨테이너 MySQL 의 호스트 포트를 13306 으로 바꾼다. 로컬에 직접 설치한 MySQL 이 3306 을 쓰는 환경이 있어서다.
Connect 와 MySQL 은 compose 네트워크 안에서 `mysql:3306` 으로 연결되므로 호스트 포트와 상관없다.

## 2. CDC 의 세 층

CDC 가 순서를 보장한다고 할 때, 그 보장은 세 층이 합쳐져서 나온다. 어느 층의 이야기인지 구분해야 무엇을 바꿨을 때 깨지는지 알 수 있다.

```
변경을 알아내는 방법      log 기반(binlog, WAL) / trigger 기반 / 쿼리(polling) 기반   ← 지금 relay 는 쿼리 기반에 가깝다
log 기반을 구현한 도구    Debezium / Maxwell, Canal / 관리형(AWS DMS 등)
Debezium 을 띄우는 방식   Kafka Connect 플러그인 / Debezium Server / Debezium Engine(앱에 내장)
```

Debezium 은 혼자 도는 서버가 아니라 Kafka Connect API(`SourceConnector`, `SourceTask`)를 구현한 라이브러리다.
binlog 를 읽어 `SourceRecord` 를 돌려주는 데까지가 Debezium 이고, 언제 보내고 언제 offset 을 저장할지는 실행기인 Connect 가 정한다.
그래서 4.3 과 4.4 의 보장은 Debezium 코드가 아니라 Kafka 코드에 있다. Engine 방식으로 앱에 넣으면 그 부분을 앱이 직접 구현해야 한다.

## 3. binlog

### 3.1 포맷에 따라 남는 것

| 포맷 | 남기는 것 | CDC 에 쓸 수 있나 |
|---|---|---|
| STATEMENT | 실행한 쿼리 | 쓸 수 없다. 어떤 행이 어떤 값이 됐는지 알려면 쿼리를 다시 실행해야 하고, `NOW()` 나 `UUID()` 는 다시 실행하면 값이 달라진다 |
| ROW | 바뀐 행의 before 와 after | 필요한 포맷이다. 계산이 끝난 값이 남아 있어 읽기만 하면 된다 |
| MIXED | 기본은 쿼리, 위험한 문장만 행 | 쓸 수 없다. 쿼리로 남은 변경을 놓친다 |

- Debezium 은 ROW 포맷이 아니면 시작하지 않는다.
- `binlog_row_image=MINIMAL` 이면 UPDATE 의 after 에 바뀐 컬럼만 남는다. outbox 는 INSERT 만 하므로 영향이 작지만, Debezium 은 FULL 을 전제로 한다.
- `READ-COMMITTED` 격리 수준에서 InnoDB 는 STATEMENT 포맷 기록을 거부한다. 지금 격리 수준만으로도 ROW 가 강제된다.
- 쿼리를 남길지 결과를 남길지는 이벤트를 설계할 때도 같은 질문이다. `OrderConfirmed` 는 결과(사실)를 담고 있어서, 복구 API 가 저장된 상태에서 같은 payload 를 다시 만들 수 있다.

### 3.2 기록 시점과 순서

- TX 의 변경은 세션별 binlog cache 에 모였다가 commit 할 때 한 덩어리로 파일에 붙는다. 그래서 TX 끼리 섞이지 않고 binlog 의 순서가 commit 순서와 같다. rollback 된 TX 는 남지 않는다.
- commit 은 InnoDB redo log 와 binlog 사이의 내부 2PC 로 이뤄진다(redo prepare, binlog 기록과 fsync, redo commit 순서). 장애 복구 때 prepare 상태의 TX 는 XID 가 binlog 에 있으면 commit, 없으면 rollback 된다. `sync_binlog=1` 과 `innodb_flush_log_at_trx_commit=1` 에서는 binlog 에 있는 것이 곧 commit 된 것이다.

### 3.3 실제 기록

실험에서 넣은 두 TX 의 binlog(`SHOW BINLOG EVENTS IN 'binlog.000023' FROM 1056`):

| Pos | Event_type | Info |
|---|---|---|
| 1056 | Anonymous_Gtid | |
| 1135 | Query | `BEGIN` (TX 1, e-101) |
| 1225 | Table_map | `table_id: 118 (loopers.order_event_outbox)` |
| 1331 | Write_rows | |
| 1492 | Xid | `COMMIT /* xid=184 */` |
| 1523 | Anonymous_Gtid | |
| 1602 | Query | `BEGIN` (TX 2, e-202-a, e-202-b) |
| 1692 | Table_map | |
| 1798 | Write_rows | e-202-a |
| 1961 | Table_map | |
| 2067 | Write_rows | e-202-b |
| 2230 | Xid | `COMMIT /* xid=188 */` |

`Table_map` 에는 컬럼 타입만 있고 이름은 없다. Debezium 이 DDL 을 따라가며 schema history topic(`schema-history.loopers`)을 쌓는 이유다.

## 4. Debezium 이 순서와 누락을 지키는 방법

이벤트 하나가 binlog 에서 topic 까지 가는 경로를 네 단계로 나눠 본다. 인용한 소스는 실제로 띄운 버전(Debezium 3.6.3, Kafka Connect 4.3.0)이다.

### 4.1 읽는 태스크는 하나다

`debezium-connector-binlog/.../BinlogConnector.java:51` (Debezium 3.6.3)

```java
if (maxTasks > 1) {
    throw new IllegalArgumentException("Only a single connector task may be started");
}
```

binlog 를 나눠 읽는 경로가 없다. Connect 를 여러 대 띄워도 binlog 를 읽는 태스크는 항상 하나이고, 나머지는 그 태스크가 죽으면 넘겨받는다.

### 4.2 binlog 이벤트를 한 스레드로 받아 한 줄 큐에 넣는다

binlog 를 받는 것은 `mysql-binlog-connector-java` 다. 이미지에 들어 있는 것은 원본(`com.zendesk`)이 아니라 Debezium 이 포크한 `io.debezium:mysql-binlog-connector-java:0.41.2` 다.

`BinaryLogClient.java:1048, 1142, 1365` (0.41.2)

```java
newNamedThread(runnable, "blc-" + hostname + ":" + port).start();

while (inputStream.peek() != -1) {
    int packetLength = inputStream.readInteger(3);
    ...
    event = eventDeserializer.nextEvent(...);
    ...
    notifyEventListeners(event);
    updateClientBinlogFilenameAndPosition(event);
}
```

- 스레드는 `blc-...` 하나다. 이 스레드가 소켓 읽기, 역직렬화, 리스너 호출을 한 루프 안에서 하고, 리스너는 for 문으로 동기 호출한다.

`BinlogStreamingChangeEventSource.java:191, 206, 225` (Debezium 3.6.3)

- Debezium 은 이 client 에 리스너를 하나 등록하고(`client.registerEventListener((event) -> onEvent(...))`), 이벤트 타입마다 handler 를 연결한다(`EXT_WRITE_ROWS` 는 `handleInsert`, `XID` 는 `handleTransactionCompletion`).
- handler 가 만든 record 는 `ChangeEventQueue` 에 들어간다. 기본 구현은 `ArrayDeque`(`DefaultQueueProvider.java:33`)이고, 넣은 순서대로 `poll()` 로 나간다.
- 큐가 차면 binlog 읽기가 기다린다(`ChangeEventQueue.java:249`, `while (queue.size() >= maxQueueSize ...) isNotFull.await(...)`). 앞의 이벤트를 앞지르는 경로가 없다.

```
blc 스레드 하나:  소켓 읽기 → 역직렬화 → onEvent → handler → 큐에 넣기 → 다음 패킷
```

TCP 에서 큐까지 가는 동안 스레드가 바뀌지 않는다. 큐가 차면 같은 스레드의 소켓 읽기도 멈추고,
`BufferedSocketInputStream`(512KB, `:32`)과 커널 수신 버퍼가 다 찬 뒤에는 TCP 윈도우가 닫혀 MySQL 쪽 송신이 기다린다.

라이브러리는 이벤트를 건너뛰고, Debezium 은 그 지점에서 멈춘다. 라이브러리가 이벤트를 흘려보내는 곳은 두 군데다.

| 라이브러리 (0.41.2) | Debezium 3.6.3 의 처리 |
|---|---|
| 역직렬화에 실패하면 lifecycle 리스너에 알리고 `continue` 해서 그 이벤트를 버리고 다음 패킷으로 간다(`BinaryLogClient.java:1169-1179`) | lifecycle 리스너가 모드 `FAIL`(기본값, `CommonConnectorConfig.java:812`)이면 `errorHandler.setProducerThrowable(...)` 을 부른다(`BinlogStreamingChangeEventSource.java:1324`) |
| 리스너가 예외를 던지면 `WARNING` 로그만 남기고 다음 리스너로 간다(`BinaryLogClient.java:1368`) | `onEvent` 가 handler 예외를 직접 잡아 `setProducerThrowable(...)` 을 부르고, `eventHandlers.clear()` 로 이후 이벤트를 무시한다(`:635`) |

두 경우 모두 `ChangeEventQueue.poll()` 이 기록된 예외를 던지게 되고(`ChangeEventQueue.java:292, 296`), 태스크가 실패한다.
offset 은 연속으로 ack 된 데까지만 저장돼 있으므로(4.4) 다시 기동하면 실패한 이벤트부터 다시 읽는다. 라이브러리의 건너뛰기를 멈추고 다시 읽기로 바꿔 놓은 구조다.

### 4.3 producer 는 한 번에 하나만 보내고 포기하지 않는다

`connect/runtime/.../Worker.java:850-859` (Kafka 4.3.0)

```java
producerProps.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, Long.toString(Long.MAX_VALUE));
producerProps.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, "false");
producerProps.put(ProducerConfig.ACKS_CONFIG, "all");
producerProps.put(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, "1");
producerProps.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, Integer.toString(Integer.MAX_VALUE));
```

실행 중인 커넥터 producer(`client.id = connector-producer-order-event-outbox-0`)의 로그에도 같은 값이 찍혔다.

```
acks = -1
delivery.timeout.ms = 2147483647
enable.idempotence = false
max.block.ms = 9223372036854775807
max.in.flight.requests.per.connection = 1
retries = 2147483647
```

- 순서는 idempotence 가 아니라 in-flight 1 로 지킨다. 앞 요청이 재시도하는 동안 뒤 요청이 끼어들 수 없다. 처리량을 포기하고 순서를 지키는 선택이다.
- 재시도할 수 있는 오류는 사실상 끝없이 재시도한다. 포기하고 다음 것을 보내는 경우가 없다.

### 4.4 offset 은 앞에서부터 연속으로 ack 된 데까지만 저장한다

`connect/runtime/.../SubmittedRecords.java:143-153` (Kafka 4.3.0)

```java
while (canCommitHead(queuedRecords)) {
    result = queuedRecords.poll().offset();
}
...
private boolean canCommitHead(Deque<SubmittedRecord> queuedRecords) {
    return queuedRecords.peek() != null && queuedRecords.peek().acked();
}
```

- 뒤 record 가 먼저 ack 돼도 앞 record 가 ack 되기 전에는 offset 이 그 너머로 가지 않는다.
- 저장은 주기적으로 한다(`offset.flush.interval.ms`, 실험에서는 10 초). 그래서 다시 기동하면 마지막으로 저장한 위치부터 순서대로 다시 보낸다. 순서는 지켜지지만 중복이 생긴다.
- Debezium 은 재시작할 때 offset 에 저장된 `event`, `row` 수만큼 건너뛴다(`BinlogStreamingChangeEventSource.java:291, 295`, 로그 `Skip N events / Skip N rows on streaming start`). TX 중간까지 보냈더라도 이미 보낸 행은 다시 보내지 않고, 저장된 offset 이후에 보낸 것만 중복된다.

### 4.5 보장과 한계

| 보장 | 근거 | 한계 |
|---|---|---|
| commit 된 것만 나간다 | binlog 에는 commit 된 TX 만 있다(3.2) | 없음 |
| 순서 | 태스크 하나(4.1), 스레드 하나와 한 줄 큐(4.2), in-flight 1 과 끝없는 재시도(4.3) | 하나가 막히면 전부 멈춘다 |
| 누락 없음 | 라이브러리의 건너뛰기를 태스크 실패로 바꾸고(4.2), 연속으로 ack 된 데까지만 offset 을 저장한다(4.4) | offset 이 가리키는 binlog 가 보존 기간(`binlog_expire_logs_seconds`, 30 일)이 지나 지워지면 이어서 읽지 못한다 |
| 중복 없음 | 보장하지 않는다 | 저장 주기 사이에 죽으면 다시 보낸다(5.3). 소비자가 eventId 로 걸러야 한다 |

## 5. 실행 결과

### 5.1 행 하나 (e-101)

| 값 | 결과 |
|---|---|
| 목적지 | `order-events-cdc` partition 2, offset 0, key `101`, header `id: e-101`, value `{"orderId":101,"seq":1}` |
| 저장된 source offset | `{"file":"binlog.000023","pos":1135,"row":1,"event":2,...}` |

`pos` 는 TX 의 `BEGIN` 위치(1135)다. `event: 2` 는 그 TX 안에서 이벤트 두 개(`Table_map`, `Write_rows`)를, `row: 1` 은 마지막 이벤트의 행 하나를 처리했다는 의미다.

### 5.2 한 TX 에 같은 주문의 행 두 개 (e-202-a, e-202-b)

| 값 | 결과 |
|---|---|
| 목적지 | partition 2 의 offset 1 에 e-202-a, offset 2 에 e-202-b. INSERT 한 순서 그대로다 |
| 저장된 source offset | `{"file":"binlog.000023","pos":1602,"row":1,"event":4,...}` |

`pos` 는 TX 2 의 `BEGIN`(1602)이고, `event: 4` 는 `Table_map` 과 `Write_rows` 두 쌍이다. COMMIT(`Xid`) 위치(2230)가 아니라 마지막으로 보낸 record 의 위치가 저장된다.

### 5.3 보낸 직후 Connect 를 강제 종료 (e-404)

INSERT 하고 0.5 초 뒤 `docker kill connect`, 이어서 `docker start connect`.

| 값 | 결과 |
|---|---|
| kill 직후 저장된 source offset | `pos: 2340, event: 2, row: 1`. 앞 TX(e-303)에 머물러 있다. e-404 는 보냈지만 offset 은 저장되지 않았다 |
| 재기동 로그 | `Found previous partition offset ... {file=binlog.000023, pos=2340, row=1, event=2}`, `Skip 2 events`, `Skip 1 rows`, `Requesting streaming from position ... 2340` 순서로 찍혔다 |
| 목적지 | e-404 가 partition 0 에 offset 0 과 1 로 두 번 들어갔다. header `__debezium.context.runId` 가 서로 다르다 |

e-303 은 건너뛰기가 동작해서 다시 나가지 않았고, e-404 만 중복됐다. 4.4 에서 본 대로 저장한 위치 이후의 것만 다시 나갔다.

같은 실험을 offset 저장 직후에 죽였을 때(e-303)는 중복이 없었다. 로그 `Committing offsets for 1 acknowledged messages` 가 kill 0.1 초 전에 찍혀 있었다. 중복이 생기는지는 저장 주기와 죽는 시점에 따라 갈린다.

### 5.4 Connect 가 내려가 있는 동안 INSERT (e-505)

`docker stop connect`, e-505 INSERT, 20 초 대기(목적지에 0 건), `docker start connect` 순서로 진행했다.

| 값 | 결과 |
|---|---|
| 재기동 뒤 목적지 | partition 0, offset 2 에 e-505 한 건 |

Connect 가 없는 동안의 변경은 binlog 에 남아 있다가, 다시 기동한 뒤 저장한 위치부터 이어 읽혀 나간다.

## 6. 세 가지 offset

| | 무엇의 위치인가 | 저장하는 곳 | 실험 값 |
|---|---|---|---|
| source offset | MySQL binlog 의 위치(file, pos, event, row) | Kafka `connect-offsets` topic (compacted) | `{"file":"binlog.000023","pos":2340,...}` |
| partition offset | topic partition 안의 record 번호 | broker log | e-404 의 0 과 1 |
| consumer offset | 소비자가 읽은 위치 | Kafka `__consumer_offsets` | W6 consumer |

source offset 은 Kafka 에 저장되지만 내용은 MySQL 의 위치다. polling relay 에는 source offset 이 없고, 행마다 있는 `status` 가 그 역할을 한다.
아직 보내지 않았다는 정보를 행마다 두면 행마다 따로 재시도한다. 이때는 순서가 깨질 수 있지만 하나가 막혀도 나머지는 나간다.
위치 하나로 두면 한 줄로 나간다. 순서는 지켜지지만 하나가 막히면 모두 멈춘다.

## 7. 앱 서버가 죽어도 되는가

된다. 5.1 부터 5.4 까지는 앱을 띄우지 않고 SQL 로 넣은 행이다. Connect 는 앱이 아니라 MySQL 의 binlog 를 본다.

| 죽는 것 | 결과 |
|---|---|
| 앱, commit 뒤 | binlog 에 남아 있으므로 나간다. W6 의 after-commit 직접 발행이 잃던 경우다 |
| 앱, commit 전 | rollback 되어 binlog 에 없으므로 나가지 않는다. 확정도 없으니 맞는 결과다 |
| Connect | 저장한 위치부터 이어 읽는다(5.4). 저장 주기 사이에 죽었으면 중복이 생긴다(5.3) |
| Connect 가 오래 | binlog 보존 기간을 넘기면 이어 읽지 못한다. 새 snapshot 이 필요하고, 그 사이의 변경은 따로 확인해야 한다 |
| MySQL | binlog 를 읽지 못하니 멈춘다. 확정도 할 수 없으므로 새로 생기는 사실도 없다 |

polling relay 도 앱이 다시 뜨면 `PENDING` 을 다시 읽으므로 앱이 죽어서 사건을 잃지는 않는다. 차이는 발행이 앱의 수명에 묶이는지다.
relay 는 앱이 떠 있어야 보내고, CDC 는 앱과 상관없이 보내지만 Connect 라는 운영 대상이 하나 늘어난다.

## 8. 기본값을 바꾸면 깨지는 곳

| 바꾸는 것 | 결과 | 근거 |
|---|---|---|
| `errors.tolerance=all` | 재시도로 해결되지 않는 전송 실패(예: `RecordTooLargeException`)를 로그만 남기고 건너뛰어 유실된다. 기본값 none 에서는 태스크가 실패하고 전체가 멈춘다 | `WorkerSourceTask.java:176` (4.3.0) |
| `event.processing.failure.handling.mode=warn` 또는 `ignore` | 역직렬화에 실패한 binlog 이벤트를 로그만 남기고 건너뛰어 유실된다. 기본값 fail 에서는 태스크가 실패하고 그 이벤트부터 다시 읽는다 | `BinlogStreamingChangeEventSource.java:1324` (3.6.3), `BinaryLogClient.java:1179` (0.41.2) |
| producer 설정 덮어쓰기 | 기본값을 넣은 뒤에 `producer.*`, `producer.override.*` 를 `putAll` 한다. idempotence 없이 in-flight 를 늘리면 재시도할 때 순서가 뒤집힐 수 있다 | `Worker.java:862, 871` (4.3.0) |
| 목적지 partition 수 | key 가 다른 partition 으로 옮겨 가는 순간 순서가 깨질 수 있다 | Kafka partitioner |
| snapshot 모드 | 시작할 때 global read lock 을 잡는다. 실험에서는 `Writes to MySQL tables prevented for a total of 00:00:00.981` 이 찍혔다(`no_data`, 스키마만 읽음). 데이터까지 읽는 모드는 테이블 크기에 따라 길어질 수 있다 | Connect 로그 |

## 9. CDC 로도 지켜지지 않는 순서

CDC 가 지키는 것은 binlog 에 기록된 순서다. 사건이 실제로 일어난 순서까지 지키지는 않는다.

- 같은 주문의 TX 들이 사건이 일어난 순서대로 commit 되는 것은 `PaymentTransactions` 가 주문 행을 먼저 잠그기 때문이다. 이런 잠금이 없으면 binlog 순서는 우연히 정해진다.
- 복구 API(`OrderEventRecovery`)가 예전 `OrderConfirmed` 를 outbox 에 새로 넣으면 binlog 에서는 나중 기록이 된다. 그 사이에 취소가 있었다면 CDC 를 써도 확정이 취소보다 늦게 도착한다.
- 그래서 소비자 쪽 판정은 여전히 필요하다. 지금 소비자는 구매 활동을 더하기만 하고 eventId 로 중복을 막으므로 도착 순서의 영향을 받지 않는다.
  상태에 따라 동작하는 소비자(예: 확정되면 출고)가 붙으면 주문별 version 이 필요하다.

## 10. 확인하지 못한 것

- `ChangeEventQueue.poll()` 은 루프를 시작할 때와 반복 중간에 예외를 확인하지만, 배치가 차거나 타임아웃으로 루프를 빠져나갈 때는 확인하지 않고 반환한다(`:283-310`).
  poll 이 시작할 때의 확인을 통과한 뒤 역직렬화 실패가 기록되고, 라이브러리가 `continue` 로 넣은 다음 이벤트까지 꺼내 반환하면
  실패한 이벤트를 건너뛴 record 가 나가고 offset 이 그 너머로 갈 수 있어 보인다. 코드로만 판단했고 재현하지 않았다.
- 4.2 에서 배압이 TCP 까지 전해진다는 것은 스레드 구조와 버퍼를 보고 판단했다. 실제로 윈도우가 닫히는 것은 관찰하지 않았다.
- exactly-once source 모드(`exactly.once.source.support`)는 켜지 않았다. 이 모드에서는 producer 가 idempotence 와 transaction 을 켜고(`Worker.exactlyOnceSourceTaskProducerConfigs`) 5.3 의 중복이 사라질 수 있다. Debezium MySQL 커넥터가 어떤 조건에서 이 모드를 지원하는지는 확인하지 않았다.
- record 가 너무 큰 경우(8 장의 첫 행)와 binlog 가 지워진 경우(7 장의 네 번째 행)는 소스와 문서로만 판단했고 실행으로 재현하지 않았다.
- polling relay 와 같은 장면을 나란히 돌려 비교하는 실험(같은 주문의 앞 행이 backoff 중일 때 등)은 아직 하지 않았다.
