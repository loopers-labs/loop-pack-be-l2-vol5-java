# W10 사고 진단·복구 상세 (AI용)

사람용 요약은 `incident-recovery.md`. 이 문서는 구현에 필요한 상세와 결정 근거·대안 비교 전체를 담는다.

## 1. 결정 기록

### 1-1. 분산 추적: Micrometer Tracing + OTel bridge → OTLP → Tempo
- 현재 `supports:logging`에 `micrometer-tracing-bridge-brave`만 있다(exporter 없음, 샘플링 기본 0.1). OTel bridge(`micrometer-tracing-bridge-otel`) + `opentelemetry-exporter-otlp`로 교체하고 `management.tracing.sampling.probability: 1.0`.
- 계측이 안 붙는 곳 두 군데를 고친다.
  - `PgHttpClient`가 정적 `RestClient.builder()`로 만든다 → 자동 설정 `RestClient.Builder` 빈을 주입받아 만든다(observation·traceparent 헤더 주입).
  - `modules:kafka`의 `KafkaConfig`가 `KafkaTemplate`을 직접 만든다 → `setObservationEnabled(true)`. streamer listener container factory도 `getContainerProperties().setObservationEnabled(true)`.
- test 프로필: OTLP 전송 끔. api 테스트는 in-memory span exporter 빈(`opentelemetry-sdk-testing`의 `InMemorySpanExporter`)으로 수집. 속성 이름은 Boot 3.4 문서로 구현 때 확인.
- 로컬: `docker/monitoring-compose.yml`에 Grafana Tempo 추가, Grafana datasource provisioning.

| 대안 | 장점 | 탈락 이유 |
|---|---|---|
| Pinpoint | 코드 변경 0, 서버맵·호출 트리 | Collector·HBase·Web 운영, 전용 프로토콜, 테스트 assert 불가 |
| OTel Java agent | 코드 변경 0, 넓은 자동 계측 | 검증이 백엔드 조회에 의존 |
| 공통 | — | 어느 방식도 Outbox 저장→다른 스레드 발행 구간을 자동으로 잇지 못함 |

### 1-2. Outbox traceparent 저장·복원
- `outbox`에 nullable `trace_parent varchar(55)` 컬럼 추가(W3C 형식 `00-{32hex}-{16hex}-{2hex}`).
- 기록: `OrderConfirmedOutboxRecorder`가 현재 trace 문맥을 application 포트(예: `TraceContextReader`)로 읽어 저장. 포트 구현은 infrastructure(Micrometer `Tracer`·`Propagator`). 문맥이 없으면 null.
- 발행: `KafkaOutboxPublisher`(infrastructure)가 저장된 traceparent를 부모로 span을 열고 그 안에서 `KafkaTemplate.send` → Kafka observation이 레코드 헤더에 같은 trace를 넣는다. null이면 새 trace.
- trace 범위: 한 요청의 인과 흐름(consumer 포함). reconcile·주간 batch는 별도 trace, orderId·eventId·snapshotId로 연결. traceId로 업무 동일성을 판단하지 않는다.
- 기존 테스트 호환: `Outbox.record(...)` 7인자는 traceParent null로 위임(호출 32곳 유지). `KafkaOutboxPublisher` 테스트용 생성자는 `Tracer.NOOP`·`Propagator.NOOP`.
- 운영 DDL(`ddl-auto: none`, 마이그레이션 도구 없음): `ALTER TABLE outbox ADD COLUMN trace_parent VARCHAR(55) NULL;` 기존 행은 null → 새 trace로 발행된다.
- 결과: 장애 3건의 Outbox는 reconcile 요청 TX에서 기록되므로 그 trace는 **원 결제 trace가 아니라 reconcile trace**다(증거로 남김).

| 대안 | 탈락 이유 |
|---|---|
| 끊김 유지·문서화 | 소비 구간을 요청 trace로 찾을 수 없고 Kafka 문맥 연결을 증명 못 함 |
| span link만 사용 | 부모-자식이 아니라 Tempo에서 한 trace로 보이지 않음. 이번 흐름은 1:1이라 부모 복원이 맞음 |

### 1-3. 구조화 로그: MDC + addKeyValue
- MDC 범위(`finally`에서 제거): 결제 시작(`OrderFacade.confirm`), reconcile·callback(`PaymentResultLookupFacade` 진입점), streamer consumer 처리. 키는 `orderId`. traceId·spanId는 tracing이 자동 설정.
- addKeyValue: 경계 사건 한 줄(2장 키 표).
- 출력: local·test 콘솔은 plain(Boot 기본 패턴에 traceId 포함). local은 `logs/{app}.json`(logstash 구조화 형식, 날짜별 롤링) 추가. dev·qa·prd는 기존 JSON 콘솔 유지.

| 기준 | MDC | addKeyValue |
|---|---|---|
| 붙는 범위 | 범위 안의 모든 로그(라이브러리 포함) | 그 한 줄 |
| 전달 비용 | 하위 메서드에 식별자를 넘기지 않음 | 로그마다 직접 |
| 이번 효용 | 적용 TX rollback 때 Spring·Hibernate 예외 로그에도 orderId → 가설 3 연결 | 사건 고유 값(거래키·결과) |
| 한계 | 스레드에 묶임, 정리 누락 시 스레드 풀에서 오염 | 라이브러리 로그엔 안 붙음 |

### 1-4. 에러 로그 파일 분리 안 함
- 이유: 에러 줄만으로는 맥락이 없고, JSON이면 `jq 'select(.level=="ERROR")'` 한 줄로 충분.
- 버린 대안: ERROR 복제 파일(보존 기간 차등·`tail -f` 장점, 이번 범위에 불필요). 한계: 롤링 시 에러도 함께 삭제.

### 1-5. 로그 집계(Loki) 미도입
- 증거는 테스트 `ListAppender`, 요청 흐름은 Tempo. compose 컨테이너 2개와 설정 대비 이득이 적음.
- 대안: jq 병합 검색(채택, 3장), Loki+Alloy(개선 후보), ELK(운영 부담 최대).

### 1-6. 비동기 로그
- 트래픽이 많다고 가정. Logback 기본은 동기(유실 없음 우선).
- 파일 JSON appender를 `AsyncAppender`로 감쌈: `queueSize=8192`, `discardingThreshold=0`, `neverBlock=true`, `maxFlushTime` 설정. 콘솔은 동기 유지.
- MDC·key-value는 enqueue 시점에 복사되므로 유지된다.

| 대안 | 탈락 이유 |
|---|---|
| 동기 파일 | 디스크 지연이 요청 지연 |
| neverBlock=false(기본) | 큐가 차면 요청 스레드 대기 |
| discardingThreshold 기본(20%) | 큐 80%부터 INFO 이하를 조용히 버려 경계 로그가 먼저 사라짐 |
- 한계: 버린 건수 지표 없음(개선 후보).

### 1-7. 업무 지표 2개
| 지표 | 정의 | label |
|---|---|---|
| `commerce.payment.unresolved` | 상태별 미종결 시도 수 | `status` ∈ REQUESTED·PENDING·UNKNOWN |
| `commerce.payment.unresolved.oldest.age.seconds` | 시스템 현재 시각 − 상태별 가장 오래된 미종결 시도 `createdAt`, 없으면 0 | `status` |
- 갱신: 스케줄(기본 10s, 테스트는 짧게)에서 DB `group by status`로 count·min(createdAt) 한 쿼리. 미종결 상태가 0건이면 해당 status는 0으로 갱신(시계열이 사라지지 않게 세 status를 항상 등록).
- 대기 시작은 createdAt(고객이 결제한 순간). updatedAt은 조회 때마다 바뀔 수 있어 제외.
- 대기시간은 주입 `Clock`이 아니라 시스템 시각으로 잰다. `createdAt`을 `BaseEntity`가 `ZonedDateTime.now()`로 기록하고, e2e는 `Clock`을 주 중간으로 고정하기 때문이다(같은 시계끼리 빼야 한다).
- 레이어: `PaymentAttemptRepository.summarizeUnresolved()`(JPQL group by 1회) → `UnresolvedPayments`(없는 상태 0) → `PaymentMonitoringFacade`(readOnly, 미종결 상태 전부 반환) → `interfaces.scheduler.UnresolvedPaymentMetrics`가 생성 시 gauge 6개를 등록하고 `payment.metrics.unresolved-refresh-interval`(기본 10s)마다 AtomicLong 값을 갱신.
- orderId·userId·traceId를 label로 쓰지 않는다.

### 1-8. 알람 (Grafana provisioning, Slack contact point)
| 규칙 | 식 | for | 의미 |
|---|---|---|---|
| 미확인 급증 | `commerce_payment_unresolved{status="UNKNOWN"} > 5` | 5m | 장애 규모 |
| 고객 장기 대기 | `commerce_payment_unresolved_oldest_age_seconds{status="UNKNOWN"} > 600` | 1m | 한 건이라도 오래 정체 |
| 에러 발생 | `rate(logback_events_total{level="error"}[1m]) > 0` | 1m | 에러 알람(로그 대신 지표) |
- notification policy: `repeat_interval 4h`, resolved 알림 1회. webhook은 환경변수로만 주입(저장소에 비밀값 없음).
- 값은 실험용 예시. 탐지 지연 = gauge 주기 + scrape 주기 + for.
- Slack logback appender(`ASYNC-SLACK`)는 주석 상태 유지.
- 파일: `docker/monitoring-compose.yml`(prometheus v2.55.1·tempo 2.6.1·grafana 11.3.0 고정), `docker/tempo/tempo.yml`, `docker/grafana/provisioning/{datasources,alerting,dashboards}`. datasource uid는 `prometheus`·`tempo`(규칙이 uid로 참조).
- Prometheus 이름: 건수 gauge는 단위를 두지 않아 `commerce_payment_unresolved`, 대기시간은 `commerce_payment_unresolved_oldest_age_seconds`. 단위를 주면 접미사가 붙어 규칙과 어긋난다.
- streamer를 함께 띄우면 `--management.server.port=8084`(수집 대상 `commerce-streamer`).
- 로컬 확인(2026-11-27): 규칙 3개·정책(repeat 4h)·대시보드 로드, 규칙 health ok, 응답 유실 1건에서 UNKNOWN=1·대기시간 증가 수집, confirm 요청 trace(PG `http post` span 포함)가 Tempo에 저장됨. Slack 실제 발송·알람 발화는 미확인.

| 대안 | 탈락 이유 |
|---|---|
| 주문마다 알림 | 같은 장애로 폭주 |
| 건수만 | 소수 장기 정체 누락 |
| 앱이 임계치 판단·발송 | 변경에 배포 필요, 앱 장애 시 알람도 멈춤 |
| ERROR 로그마다 Slack | 폭주 |
| `DuplicateMessageFilter` | 전체 appender에 적용돼 파일 증거도 버림, 시간창·요약 없음 |

### 1-9. 사고 검증 e2e
- `apps/commerce-api` `CheckoutIncidentE2ETest`, `@Tag("e2e")`, `PaymentFlowTestSupport` 상속, `@Test` 하나에서 단계 메서드를 순서대로 호출.
- 프로세스: api는 테스트 JVM(`RANDOM_PORT`, `management.server.port=0`, `@LocalManagementPort`). streamer는 `StreamerProcess`. batch는 신규 `BatchProcess`(`java -jar` + `--job.name=weeklyRanking periodStart=… revision=1`, 종료 코드 0 대기, 같은 datasource 속성 전달).
- `e2eTest` 태스크에 `dependsOn(":apps:commerce-batch:bootJar")`, `batch.jar` 시스템 속성 추가.

| 대안 | 탈락 이유 |
|---|---|
| 개발계 수동 | 반복 불가, 수정 때 누락 |
| 기본 test 포함 | 빌드마다 수 분 |
| `@TestMethodOrder` 단계 분리 | 테스트별 정리 차단 필요, 실패 연쇄 |
| 세 앱 모두 jar | fixture·시계·span·로그 수집을 직접 못 다룸 |

### 1-10. FakePgServer 제어
- `dropResponseFor(Set<String> providerOrderIds)` / 해제: 지정 ID의 create는 거래(PENDING)를 저장한 뒤 응답 없이 연결을 끊는다. 그 외는 기존 `onCreate` 동작.
- `duplicateCreates()`: 같은 orderId의 두 번째 이후 create 시도를 기록(응답과 무관하게 유지).
- 요청 기록에 `traceparent` 헤더 보존.
- 최종 SUCCESS 전환은 기존 `setStatus`. callback은 테스트가 callback API를 직접 호출(fixture는 callback을 보내지 않음).

| 대안 | 탈락 이유 |
|---|---|
| 호출 순서로 drop 전환 | 실행 순서 우연 의존 |
| read timeout 지연 | 건당 3s 대기, 본질은 같음 |

### 1-11. 시계·주간
- api 테스트에만 고정 Clock(`@Primary`, 주 중간 수요일 12:00Z 등). batch `periodStart` = 그 주 월요일, `revision=1`. streamer는 이벤트 confirmedAt 저장이라 영향 없음.
- 대가: relay 재시도 간격이 지나지 않음(이번 시나리오는 재시도 없음).
- 버린 대안: 실제 시계(월요일 00:00Z 근처 실행 시 기대값이 두 주로 갈림).

### 1-12. 증거 위치
- `build/incident-runs/{runId}/`(runId = UTC 시각 + 짧은 난수). 실패 실행도 보존. 제출 1회분만 `docs/incident/week10/`로 복사·커밋.

### 1-13. relay는 실제 scheduler
- 테스트 속성 `outbox.relay.enabled=true`, 짧은 fixed-delay. Awaitility로 PUBLISHED 대기.

### 1-14. 엣지 케이스
- 0건·복수·금액 불일치·계속 PENDING은 기존 `PaymentIntegrationTest`가 담당. 사고 e2e에 중복하지 않고 회귀로 실행.

## 2. 경계 로그 키 (api·streamer 공통 이름)

| event | 위치 | key-value | MDC | 수준 |
|---|---|---|---|---|
| `payment.create.result` | `PgHttpClient` create 응답 해석 | `providerOrderId`, `result`(ACCEPTED·REJECTED·UNKNOWN), `cause`(IO·TIMEOUT·BAD_RESPONSE, UNKNOWN일 때), `transactionKey`(ACCEPTED일 때) | orderId | INFO, UNKNOWN은 WARN |
| `payment.lookup.result` | `PaymentResultLookupFacade.syncWithPg`(PG 조회 직후) | `providerOrderId`, `outcome`(ANSWERED·KEY_NOT_FOUND·LOOKUP_UNAVAILABLE), `count`, `pgStatus`·`transactionKey`(1건일 때) | orderId | INFO, 조회 불가는 WARN |
| `payment.apply.committed` | 결과 반영 TX 뒤(`OrderFacade.confirm`, `syncWithPg`) — `PaymentBoundaryLog.applyLogged` | `providerOrderId`, `from`, `to`, `orderStatus` | orderId | INFO |
| `payment.apply.rolled_back` | 같은 곳, TX 예외 시 기록 후 재던짐 | `providerOrderId`, `from`, `exception` | orderId | ERROR |
| `outbox.published` | `KafkaOutboxPublisher` 발행 성공 | `eventId`, `orderId`, `partition`, `offset` | — | INFO |
| `purchase.activity.saved` | streamer `PurchaseActivityFacade` | `eventId`, `orderId`, `outcome`(SAVED·DUPLICATE) | orderId | INFO |
- 시도 식별은 `providerOrderId`(주문과 1:1, `LP-ORD-%010d`)로 한다. attemptKey는 반영 경로의 값 객체에 없어 로그에 넣지 않는다.
- 메시지에도 핵심 값을 적어 plain 콘솔에서 읽히게 한다. JSON 파일은 key-value·MDC·traceId가 필드로 들어간다(local 실행으로 확인).
- 카드번호·인증 헤더·사용자 개인정보는 남기지 않는다. 합성 식별자만.

## 3. 진단 질의 (runbook에 그대로 사용)
```bash
# 주문 하나의 앱 간 흐름
jq -c 'select(.orderId=="42") | {t: ."@timestamp", app: input_filename, event, result, outcome, message}' \
  logs/commerce-api.json logs/commerce-streamer.json | jq -s 'sort_by(.t)[]'
# 응답 유실 후보
jq -c 'select(.event=="payment.create.result" and .result=="UNKNOWN")' logs/commerce-api.json
# 내부 반영 실패 흔적
jq -c 'select(.event=="payment.apply.rolled_back" or .level=="ERROR")' logs/commerce-api.json
```
DB 진단은 SELECT만(UNKNOWN 시도 목록·나이, 주문·재고·Outbox 상태). UPDATE·재결제 명령은 runbook에 넣지 않는다.

## 4. e2e 단계별 assert

준비: 상품 1개(재고 25), 사용자 A(baseline)·B(fault). B는 포인트 충전·쿠폰 1장 발급, 기준값 기록. 기대 재고: baseline 후 15, fault 후 5, recovery 후 5.

| 단계 | 조작 | assert |
|---|---|---|
| BASELINE | drop OFF 확인 → A 주문 10건 결제 → fixture `setStatus(SUCCESS)` → callback API | 시도 SUCCEEDED 10·UNKNOWN 0, PG SUCCESS 10, createCalls 10, health UP, gauge UNKNOWN 0 |
| FAULT | 장애 3건 providerOrderId 지정 drop → B 주문 10건 결제 → 7건 SUCCESS+callback, 3건 SUCCESS만(callback 보류) | 성공 7·UNKNOWN 3, PG SUCCESS 10(누적 20), health UP, gauge UNKNOWN 3(유한 대기), 3건 `payment.create.result=UNKNOWN cause=IO`, 재고 5 |
| 진단·보존 | fixture 거래 읽기 조회, 경계 로그 수집, 가설 판정, fault 파일 저장·hash | 진단 전후 commerce 상태 동일, 가설 2 지지·3 기각(`apply.rolled_back` 없음) |
| RECOVERY | drop 해제·OFF 확인 → B로 3건 reconcile → 1건 reconcile+callback 반복, A로 B 주문 reconcile 1회 | 같은 10건 성공 10·UNKNOWN 0, createCalls 증가 0, duplicateCreates 0, 재고 5, 포인트·쿠폰 기준값 동일, 반복 후 Outbox 행 수 불변, 타인 reconcile 404, gauge UNKNOWN 0 |
| 후속 | relay 대기 → streamer 소비 대기 → BatchProcess → 랭킹 API | Outbox 20건 PUBLISHED, 구매 활동 20행(주문당 1), 랭킹 점수 20·1위, fault hash 재검증 일치 |
| 추적 | (단계 중 수집) | PG 요청 `traceparent`의 traceId = create span traceId, Kafka 헤더 traceId = 해당 Outbox `trace_parent` traceId, streamer 로그에 같은 traceId, 장애 주문 Outbox trace ≠ 원 결제 trace |
| finally | 내가 켠 drop 해제, streamer 종료 | 거래·증거는 지우지 않음 |

## 5. 가설 판정표 (diagnosis.json)
| 가설 | 예상 | 실제 판정 규칙 |
|---|---|---|
| PG 처리 지연 | 조회 PENDING | 3건 조회가 SUCCESS면 기각 |
| PG 성공 응답 유실 | create UNKNOWN(IO·TIMEOUT), PG SUCCESS, 적용 시도 없음 | 세 조건 모두면 지지 |
| 내부 반영 실패 | 적용 시도 + rollback | `apply.rolled_back` 없으면 기각, 경계 로그 자체가 없으면 "미확정" |

## 6. 증거 구조 (`build/incident-runs/{runId}/`)
| 파일 | 내용 |
|---|---|
| `run.json` | runId, 시작·종료 시각, git commit, 고정 clock 값, 단계별 결과·중간 실패 |
| `baseline.json`·`fault.json`·`recovery.json` | 관찰 시각, 주문별 {orderId, attemptKey, providerOrderId, transactionKey, attemptStatus, orderStatus, pgStatus}, createCalls·duplicateCreates, 재고, health 응답 원문 |
| `*-prometheus.txt` | 단계별 Prometheus 응답 원문(JSON 숫자로 바꾸지 않음) |
| `fault-logs.json` | 수집한 경계 로그(event·key-value·MDC·traceId) |
| `diagnosis.json` | 가설별 예상·실제·판정 |
| `downstream.json` | Outbox(eventId·상태), 구매 활동, snapshotId, 랭킹 응답 |
| `SHA256SUMS` | 각 파일 hash. fault 파일 hash는 recovery 후 재검증 |
파일 존재만으로 통과시키지 않는다. assertion은 같은 식별자·상태·효과 수를 검증한다.

## 7. 테스트 이름 목록 (태스크 때 확정)
- e2e: `CheckoutIncidentE2ETest.responseLostPayments_recoverWithoutNewCreate_andReachWeeklyRanking`
- 단위·통합 후보(규칙이 있는 곳만):
  - 미확인 요약: 상태별 건수·가장 오래된 나이 계산, 0건이면 0
  - Outbox 기록: 현재 trace 문맥이 있으면 traceparent 저장, 없으면 null
  - Outbox 발행: 저장된 traceparent를 부모로 발행(레코드 헤더 traceId 일치) — 기존 Outbox 통합 테스트 확장 검토
- 회귀: `PaymentIntegrationTest`, `CouponIssueConcurrencyTest`, `RankingJobRestartTest`, 각 앱 `check`(Checkstyle·ArchUnit).

## 8. runbook 목차 (`docs/runbook/week10.md`)
증상(health UP·"결제 확인 중"·알람) → 먼저 보존할 기록(지표 원문·경계 로그·DB SELECT) → 경쟁 가설과 구분 질의 → 조치 전제(새 입력 중단, 대상 ID 고정, 권한) → 조치(본인 reconcile, 같은 거래 조회만) → 중단 조건(조회 실패·0건·복수·금액 불일치·계속 PENDING이면 상태 보존 후 중단) → 복구 확인(같은 ID 상태·create 증가 0·재고·Outbox·활동·랭킹) → 재진입(실패 상태 보존, 안전한 재조회 간격) → 후속 개선.
