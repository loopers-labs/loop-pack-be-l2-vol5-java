# CDC 실험 — Debezium 은 outbox 를 어떻게 순서대로, 빠짐없이 꺼내는가

W7 의 polling relay 는 outbox 를 **행 단위**로 꺼낸다. 이 문서는 같은 outbox 테이블을 **binlog 로 꺼내는 쪽**(Debezium)을
실제로 띄워 보고, 그것이 순서와 누락을 무엇에 기대어 지키는지 소스와 실행 결과로 확인한다.
relay 를 CDC 로 바꿀지는 **정하지 않았다**. 바꿀 때 무엇을 얻고 잃는지의 근거를 모으는 문서다.

CDC 는 outbox 의 대안이 아니라 relay 의 대안이다. outbox 는 **무엇을 보낼지**(확정과 같은 TX 에 남는 사실)를,
relay 와 CDC 는 **어떻게 꺼낼지**를 맡는다.

## 1. 구성

| 구성요소 | 버전 · 설정 | 출처 |
|---|---|---|
| MySQL | 8.0, `log_bin=ON` · `binlog_format=ROW` · `binlog_row_image=FULL` · `sync_binlog=1` · `server_id=1` · `gtid_mode=OFF` · `transaction_isolation=READ-COMMITTED` | `docker/infra-compose.yml` 그대로. 8.0 기본값이 이미 CDC 조건을 채운다 |
| Kafka | `bitnamilegacy/kafka:3.5.1` 단일 broker | `docker/infra-compose.yml` 그대로 |
| Kafka Connect | 4.3.0 (Debezium 이미지에 든 것) | `docker/cdc-compose.yml` |
| Debezium | MySQL 커넥터 3.6.3.Final | `quay.io/debezium/connect:3.6.3.Final` |
| 커넥터 | `order_event_outbox` 만 읽는다. outbox event router 로 `record_key` → key, `payload` → value, `topic` 컬럼 + `-cdc` → 목적지 topic, `event_id` → header `id` | `docker/cdc/outbox-connector.json` |

목적지를 `order-events-cdc` 로 나눈 것은 앱의 relay 가 같은 행을 `order-events` 로 보내기 때문이다. 두 경로가 섞이지 않고 나란히 비교된다.

```bash
docker compose -f docker/infra-compose.yml -f docker/cdc-compose.yml up -d mysql kafka kafka-init
docker exec -i docker-mysql-1 mysql -uroot -proot loopers < docker/cdc/outbox-table.sql   # 앱 없이 실험할 때
docker compose -f docker/infra-compose.yml -f docker/cdc-compose.yml up -d connect
curl -X POST localhost:8083/connectors -H 'Content-Type: application/json' -d @docker/cdc/outbox-connector.json
```

`cdc-compose.yml` 은 컨테이너 MySQL 의 호스트 포트를 13306 으로 바꾼다. 로컬에 직접 설치한 MySQL 이 3306 을 쓰는 환경이 있어서다.
Connect 와 MySQL 은 compose 네트워크 안에서 `mysql:3306` 으로 만나므로 호스트 포트와 무관하다.

## 2. CDC 의 세 층

"CDC 가 순서를 보장한다"는 말은 세 층이 합쳐진 결과다. 어느 층의 이야기인지 구분해야 무엇을 바꾸면 깨지는지 보인다.

```
변경을 어떻게 알아내나   log 기반(binlog · WAL) / trigger 기반 / 쿼리(polling) 기반   ← 지금 relay 는 쿼리 기반에 가깝다
log 기반을 구현한 도구    Debezium / Maxwell · Canal / 관리형(AWS DMS 등)
Debezium 을 띄우는 방식   Kafka Connect 플러그인 / Debezium Server / Debezium Engine(앱에 내장)
```

Debezium 은 스스로 도는 서버가 아니라 **Kafka Connect API(`SourceConnector` · `SourceTask`)를 구현한 라이브러리**다.
binlog 를 읽어 `SourceRecord` 를 돌려주는 데까지가 Debezium 이고, 언제 보내고 언제 offset 을 저장할지는 실행기(Connect)가 정한다.
그래서 아래 4.3 · 4.4 의 보장은 Debezium 코드가 아니라 **Kafka 코드**에 있다. Engine 방식으로 앱에 넣으면 그 부분을 앱이 다시 짜야 한다.

## 3. binlog

### 3.1 무엇이 남나 — 포맷

| 포맷 | 남기는 것 | CDC |
|---|---|---|
| STATEMENT | 실행한 쿼리 | 쓸 수 없다. 어떤 행이 어떤 값이 됐는지 알려면 다시 실행해야 하고, `NOW()` · `UUID()` 는 다시 실행하면 값이 다르다 |
| ROW | 바뀐 행(before · after) | 필요한 포맷. 값이 이미 계산된 결과라 읽기만 하면 된다 |
| MIXED | 기본은 쿼리, 위험한 문장만 행 | 쓸 수 없다. 쿼리로 남은 변경을 놓친다 |

- Debezium 은 ROW 가 아니면 시작하지 않는다.
- `binlog_row_image=MINIMAL` 이면 UPDATE 의 after 에 바뀐 컬럼만 남는다. outbox 는 INSERT 만 하므로 영향이 작지만 Debezium 은 FULL 을 전제한다.
- `READ-COMMITTED` 에서 InnoDB 는 STATEMENT 포맷 쓰기를 거부한다. 지금 격리 수준이 이미 ROW 를 강제한다.
- 쿼리를 남기느냐 결과를 남기느냐는 이벤트 설계와 같은 질문이다. `OrderConfirmed` 는 결과(사실)를 싣고, 그래서 복구 API 가 저장된 상태에서 같은 payload 를 다시 만들 수 있다.

### 3.2 언제, 어떤 순서로 남나

- TX 의 변경은 세션별 binlog cache 에 모였다가 **commit 때 한 덩어리로** 파일에 붙는다. TX 끼리 섞이지 않고 **binlog 순서 = commit 순서**다. rollback 된 TX 는 남지 않는다.
- commit 은 InnoDB redo 와 binlog 사이의 내부 2PC 다(redo prepare → binlog 기록 · fsync → redo commit). 복구 때 prepare 된 TX 는 XID 가 binlog 에 있으면 commit, 없으면 rollback 된다. `sync_binlog=1` · `innodb_flush_log_at_trx_commit=1` 에서 **binlog 에 있는 것 = commit 된 것**이다.

### 3.3 실제 모양

실험에서 넣은 두 TX 의 binlog(`SHOW BINLOG EVENTS IN 'binlog.000023' FROM 1056`):

| Pos | Event_type | Info |
|---|---|---|
| 1056 | Anonymous_Gtid | |
| 1135 | Query | `BEGIN` — TX 1 (e-101) |
| 1225 | Table_map | `table_id: 118 (loopers.order_event_outbox)` |
| 1331 | Write_rows | |
| 1492 | Xid | `COMMIT /* xid=184 */` |
| 1523 | Anonymous_Gtid | |
| 1602 | Query | `BEGIN` — TX 2 (e-202-a, e-202-b) |
| 1692 | Table_map | |
| 1798 | Write_rows | e-202-a |
| 1961 | Table_map | |
| 2067 | Write_rows | e-202-b |
| 2230 | Xid | `COMMIT /* xid=188 */` |

`Table_map` 에는 컬럼 **타입**만 있고 이름은 없다. Debezium 이 DDL 을 따라가며 schema history topic(`schema-history.loopers`)을 쌓는 이유다.

## 4. Debezium 은 어떻게 순서와 누락을 지키는가

이벤트 하나가 binlog 에서 topic 까지 가는 경로를 따라 네 단계다. 인용은 실제로 띄운 버전(Debezium 3.6.3, Kafka Connect 4.3.0)의 소스다.

### 4.1 읽는 태스크는 하나다

`debezium-connector-binlog/.../BinlogConnector.java:51` (Debezium 3.6.3)

```java
if (maxTasks > 1) {
    throw new IllegalArgumentException("Only a single connector task may be started");
}
```

binlog 를 나눠 읽는 경로가 없다. Connect 를 여러 대 띄워도 binlog 를 읽는 것은 언제나 태스크 하나이고, 나머지는 그것이 죽었을 때 넘겨받는다.

### 4.2 binlog 이벤트를 한 스레드로, 한 줄 큐에 넣는다

binlog 를 받는 것은 `mysql-binlog-connector-java` 다. 이미지에 든 것은 원본(`com.zendesk`)이 아니라 Debezium 이 포크한 `io.debezium:mysql-binlog-connector-java:0.41.2` 다.

`BinaryLogClient.java:1048 · 1142 · 1365` (0.41.2)

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

- 스레드는 `blc-...` **하나**다. 그 스레드가 소켓 읽기 → 역직렬화 → 리스너 호출을 한 루프에서 하고, 리스너는 for 문으로 동기 호출한다.

`BinlogStreamingChangeEventSource.java:191 · 206 · 225` (Debezium 3.6.3)

- Debezium 은 그 client 에 리스너 **하나**를 등록하고(`client.registerEventListener((event) -> onEvent(...))`), 이벤트 타입마다 handler 를 매핑한다(`EXT_WRITE_ROWS → handleInsert`, `XID → handleTransactionCompletion`).
- handler 가 만든 record 는 `ChangeEventQueue` 에 들어간다. 기본 구현은 `ArrayDeque`(`DefaultQueueProvider.java:33`)이고, 넣은 순서대로 `poll()` 로 나간다.
- 큐가 차면 binlog 읽기가 기다린다(`ChangeEventQueue.java:249`, `while (queue.size() >= maxQueueSize ...) isNotFull.await(...)`). 앞지르는 경로가 없다.

```
blc 스레드 하나:  소켓 읽기 → 역직렬화 → onEvent → handler → 큐에 넣기 → 다음 패킷
```

TCP 에서 큐까지 스레드가 바뀌는 곳이 없다. 큐가 차면 같은 스레드의 소켓 읽기도 멈추고,
`BufferedSocketInputStream`(512KB, `:32`)과 커널 수신 버퍼가 찬 뒤 TCP 윈도우가 닫혀 MySQL 쪽 송신이 기다린다.

**라이브러리는 건너뛰고, Debezium 은 멈춘다.** 라이브러리는 두 곳에서 이벤트를 흘려보낸다.

| 라이브러리 (0.41.2) | Debezium 3.6.3 이 바꾸는 것 |
|---|---|
| 역직렬화 실패 → lifecycle 리스너에 알리고 `continue`. 이벤트를 버리고 다음 패킷으로 간다(`BinaryLogClient.java:1169-1179`) | lifecycle 리스너가 모드 `FAIL`(기본값, `CommonConnectorConfig.java:812`)이면 `errorHandler.setProducerThrowable(...)`(`BinlogStreamingChangeEventSource.java:1324`) |
| 리스너 예외 → `WARNING` 로그만 남기고 다음 리스너로(`BinaryLogClient.java:1368`) | `onEvent` 가 handler 예외를 직접 잡아 `setProducerThrowable(...)` 하고 `eventHandlers.clear()` 로 이후 이벤트를 무시한다(`:635`) |

두 경로 모두 `ChangeEventQueue.poll()` 이 기록된 예외를 던지게 하고(`ChangeEventQueue.java:292 · 296`), 태스크가 실패한다.
offset 은 연속 ack 까지만 저장돼 있으므로(4.4) 재기동하면 실패한 이벤트부터 다시 읽는다. 건너뛰기를 **멈추고 다시 읽기**로 바꾼 것이다.

### 4.3 producer 는 한 번에 하나만 보내고 포기하지 않는다

`connect/runtime/.../Worker.java:850-859` (Kafka 4.3.0)

```java
producerProps.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, Long.toString(Long.MAX_VALUE));
producerProps.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, "false");
producerProps.put(ProducerConfig.ACKS_CONFIG, "all");
producerProps.put(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, "1");
producerProps.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, Integer.toString(Integer.MAX_VALUE));
```

실행 중인 커넥터 producer(`client.id = connector-producer-order-event-outbox-0`)의 로그에도 그대로 찍혔다.

```
acks = -1
delivery.timeout.ms = 2147483647
enable.idempotence = false
max.block.ms = 9223372036854775807
max.in.flight.requests.per.connection = 1
retries = 2147483647
```

- 순서는 idempotence 가 아니라 **in-flight 1** 로 지킨다. 앞 요청이 재시도하는 동안 뒤 요청이 끼어들 수 없다. 처리량을 내주고 순서를 산다.
- 재시도 가능한 오류는 사실상 무한히 재시도한다. 포기하고 다음 것을 보내는 일이 없다.

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
- 저장은 주기적이다(`offset.flush.interval.ms`, 실험은 10 초). 그래서 재기동하면 **마지막 저장 위치부터 순서대로 다시 보낸다.** 순서는 지켜지고 중복은 생긴다.
- Debezium 은 재시작할 때 offset 의 `event` · `row` 만큼 건너뛴다(`BinlogStreamingChangeEventSource.java:291 · 295`, 로그 `Skip N events / Skip N rows on streaming start`). TX 중간까지 보냈어도 이미 보낸 행은 다시 보내지 않는다. 저장된 offset 이후에 보낸 것만 중복된다.

### 4.5 정리

| 보장 | 기대는 곳 | 한계 |
|---|---|---|
| commit 된 것만 나간다 | binlog 에는 commit 된 TX 만 있다(3.2) | — |
| 순서 | 태스크 하나(4.1) → 스레드 하나 · 한 줄 큐(4.2) → in-flight 1 · 무한 재시도(4.3) | 하나가 막히면 **전부** 멈춘다 |
| 누락 없음 | 라이브러리의 건너뛰기를 태스크 실패로 바꿈(4.2) → 연속 ack 까지만 offset 저장(4.4) | offset 이 가리키는 binlog 가 보존 기간(`binlog_expire_logs_seconds`, 30 일)을 넘겨 지워지면 이어 읽지 못한다 |
| 중복 없음 | **보장하지 않는다** | 저장 주기 사이에 죽으면 다시 보낸다(5.3). 소비자의 eventId 판정이 필요하다 |

## 5. 실행 결과

### 5.1 행 하나 — e-101

| 값 | 결과 |
|---|---|
| 목적지 | `order-events-cdc` partition 2, offset 0, key `101`, header `id: e-101`, value `{"orderId":101,"seq":1}` |
| 저장된 source offset | `{"file":"binlog.000023","pos":1135,"row":1,"event":2,...}` |

`pos` 는 TX 의 `BEGIN`(1135)이다. `event: 2` 는 그 TX 안에서 이벤트 둘(`Table_map`, `Write_rows`)을, `row: 1` 은 마지막 이벤트의 행 하나를 처리했다는 뜻이다.

### 5.2 한 TX 에 같은 주문의 행 둘 — e-202-a, e-202-b

| 값 | 결과 |
|---|---|
| 목적지 | partition 2, offset 1 → e-202-a, offset 2 → e-202-b. INSERT 순서 그대로 |
| 저장된 source offset | `{"file":"binlog.000023","pos":1602,"row":1,"event":4,...}` |

`pos` 는 TX 2 의 `BEGIN`(1602), `event: 4` 는 `Table_map` · `Write_rows` 두 쌍이다. COMMIT(`Xid`) 위치(2230)가 아니라 **마지막으로 보낸 record 의 위치**가 저장된다.

### 5.3 보낸 직후 Connect 를 강제 종료 — e-404

INSERT 0.5 초 뒤 `docker kill connect`, 이어서 `docker start connect`.

| 값 | 결과 |
|---|---|
| kill 직후 저장된 source offset | `pos: 2340, event: 2, row: 1` — 앞 TX(e-303)에 머물러 있다. e-404 는 보냈지만 offset 은 저장되지 않았다 |
| 재기동 로그 | `Found previous partition offset ... {file=binlog.000023, pos=2340, row=1, event=2}` → `Skip 2 events` · `Skip 1 rows` → `Requesting streaming from position ... 2340` |
| 목적지 | e-404 가 partition 0 에 **offset 0 과 1 로 두 번**. header `__debezium.context.runId` 가 서로 다르다 |

e-303 은 다시 나가지 않았다(건너뛰기가 동작). e-404 만 중복됐다. 4.4 의 "저장 위치 이후만 다시 보낸다"가 그대로 보인다.

같은 실험을 저장 직후에 죽였을 때(e-303)는 중복이 없었다. 로그 `Committing offsets for 1 acknowledged messages` 가 kill 0.1 초 전에 찍혔다. 중복은 **저장 주기와 죽는 시점의 관계**로 생긴다.

### 5.4 Connect 가 내려가 있는 동안 INSERT — e-505

`docker stop connect` → e-505 INSERT → 20 초 대기(목적지에 0 건) → `docker start connect`.

| 값 | 결과 |
|---|---|
| 재기동 뒤 목적지 | partition 0, offset 2 에 e-505 한 건 |

Connect 가 없는 동안의 변경은 binlog 에 남아 있다가 재기동 후 저장 위치부터 이어 읽혀 나간다.

## 6. offset 이 셋이다

| | 무엇의 위치 | 저장 | 실험에서 |
|---|---|---|---|
| source offset | **MySQL binlog** 위치(file · pos · event · row) | Kafka `connect-offsets` topic (compacted) | `{"file":"binlog.000023","pos":2340,...}` |
| partition offset | topic partition 안의 record 번호 | broker log | e-404 의 0 과 1 |
| consumer offset | 소비자가 어디까지 읽었나 | Kafka `__consumer_offsets` | W6 consumer |

source offset 은 Kafka 에 저장되지만 내용은 MySQL 의 위치다. polling relay 에는 source offset 이 없고, **행마다의 `status`** 가 그 역할을 한다.
"아직 안 보냄"을 행마다 들면 행마다 따로 재시도하고(순서가 깨질 수 있다, 하나가 막혀도 나머지는 나간다),
위치 하나로 들면 한 줄로 나간다(순서가 지켜진다, 하나가 막히면 모두 멈춘다).

## 7. 우리 서버가 죽어도 되는가

된다. 5.1 ~ 5.4 는 **앱을 띄우지 않고** SQL 로 넣은 행이다. Connect 는 앱이 아니라 MySQL 의 binlog 를 본다.

| 무엇이 죽나 | 결과 |
|---|---|
| 앱 — commit 뒤 | binlog 에 남았으므로 나간다. W6 의 after-commit 직접 발행이 잃던 경우다 |
| 앱 — commit 전 | rollback, binlog 에 없다. 나가지 않는다(확정도 없으니 맞다) |
| Connect | 저장 위치부터 이어 읽는다(5.4). 저장 주기 사이면 중복(5.3) |
| Connect 가 오래 | binlog 보존 기간을 넘기면 이어 읽지 못한다. 새 snapshot 이 필요하고 그 사이 변경은 별도로 확인해야 한다 |
| MySQL | binlog 를 못 읽으니 멈춘다. 확정도 못 하므로 새로 생기는 사실도 없다 |

polling relay 도 앱 재기동 후 PENDING 을 다시 읽으므로 앱이 죽어서 잃지는 않는다. 차이는 **발행이 앱의 수명에 묶이는가**다.
relay 는 앱이 떠 있어야 나가고, CDC 는 앱과 무관하게 나가는 대신 Connect 라는 운영 대상이 하나 는다.

## 8. 기본값을 바꾸면 깨지는 곳

| 바꾸는 것 | 결과 | 근거 |
|---|---|---|
| `errors.tolerance=all` | 재시도로 안 되는 전송 실패(예: `RecordTooLargeException`)를 로그만 남기고 **건너뛴다** — 유실. 기본값 none 에서는 태스크가 실패하고 전체가 멈춘다 | `WorkerSourceTask.java:176` (4.3.0) |
| `event.processing.failure.handling.mode=warn` · `ignore` | 역직렬화에 실패한 binlog 이벤트를 로그만 남기고 **건너뛴다** — 유실. 기본값 fail 에서는 태스크가 실패하고 그 이벤트부터 다시 읽는다 | `BinlogStreamingChangeEventSource.java:1324` (3.6.3), `BinaryLogClient.java:1179` (0.41.2) |
| producer 설정 덮어쓰기 | 기본값을 넣은 **뒤**에 `producer.*` · `producer.override.*` 를 `putAll` 한다. idempotence 없이 in-flight 를 늘리면 재시도 때 순서가 뒤집힐 수 있다 | `Worker.java:862 · 871` (4.3.0) |
| 목적지 partition 수 | key 가 다른 partition 으로 옮겨 가는 순간 순서가 깨질 수 있다 | Kafka partitioner |
| snapshot 모드 | 시작할 때 global read lock 을 잡는다. 실험에서 `Writes to MySQL tables prevented for a total of 00:00:00.981`(`no_data`, 스키마만 읽음). 데이터까지 읽는 모드는 테이블 크기에 따라 길어질 수 있다 | Connect 로그 |

## 9. CDC 로도 지켜지지 않는 순서

CDC 가 지키는 것은 **binlog 순서**이지 **업무 순서**가 아니다.

- 같은 주문의 TX 끼리 commit 순서가 업무 순서가 되는 것은 `PaymentTransactions` 가 주문 행을 먼저 잠그기 때문이다. 잠금이 없으면 binlog 순서는 우연의 순서다.
- 복구 API(`OrderEventRecovery`)가 옛 `OrderConfirmed` 를 outbox 에 새로 넣으면 binlog 에서는 **나중**이다. 그 사이 취소가 있었다면 CDC 로도 확정이 취소 뒤에 도착한다.
- 그래서 소비자 쪽 판정은 그대로 필요하다. 지금 소비자는 덧셈(구매 활동)만 하고 eventId 로 중복을 막으므로 도착 순서에 둔감하다.
  상태에 따라 행동하는 소비자(예: 확정되면 출고)가 붙으면 주문별 version 이 필요하다(`beyond-outbox.md` 1 번).

## 10. 확인하지 못한 것

- `ChangeEventQueue.poll()` 은 루프 시작과 반복 중간에 예외를 확인하지만, 배치가 차거나 타임아웃으로 루프를 나갈 때는 확인하지 않고 반환한다(`:283-310`).
  poll 이 시작 확인을 통과한 뒤 역직렬화 실패가 기록되고, 라이브러리가 `continue` 로 넣은 다음 이벤트까지 꺼내 반환하면
  실패한 이벤트를 건너뛴 record 가 나가고 offset 이 그 너머로 갈 수 있어 보인다. 코드로만 판단했고 재현하지 않았다.
- 4.2 의 TCP 까지 내려가는 배압은 스레드 구조와 버퍼로 판단했다. 실제로 윈도우가 닫히는 것을 관찰하지 않았다.
- exactly-once source 모드(`exactly.once.source.support`)는 켜지 않았다. 그 모드에서는 producer 가 idempotence · transaction 을 켜고(`Worker.exactlyOnceSourceTaskProducerConfigs`), 5.3 의 중복이 사라질 수 있다. Debezium MySQL 커넥터가 어느 조건에서 지원하는지 확인하지 않았다.
- record 가 너무 큰 경우(8 의 첫 행)와 binlog 가 지워진 경우(7 의 넷째 행)는 소스와 문서로만 판단했다. 실행으로 재현하지 않았다.
- polling relay 와 같은 장면을 나란히 돌린 비교(같은 주문의 앞 행 backoff 등)는 아직 하지 않았다.
