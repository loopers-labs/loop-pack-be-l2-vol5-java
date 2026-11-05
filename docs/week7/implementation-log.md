# 7주차 구현 기록

## 현재 상태

- 완료: Outbox 모델과 TX C 로컬 원자성, 단일 relay와 직접 전송 교체, 두 중단 지점의 hook 기반 검증, 주문 API부터 구매 활동까지의 E2E, W6 누락 재현과 복구.
- 운영: 조회·재개·재처리 절차와 보존 정책을 [운영 절차](operations.md)에 정리했다. 대상 eventId 전용 도구는 두지 않았다.
- 보완: 스케줄러 등록 조건 수정, `ProducerMaxBlockExperimentTest`를 W7 경로로 교체([6절](#6-스케줄러-등록-조건과-maxblock-실험-보완)).
- 전송 방식: 저장 payload를 문자열 그대로 전송([7절](#7-저장-payload를-문자열-그대로-전송)).
- 실험: 장애 상황과 Outbox 유무 비교(13·14절), relay 상한·구조 비교·전체 경로 부하(15~18절), 운영 리허설(19절). 모두 실험 코드이며 제품 relay와 설정(10초·200건)은 바꾸지 않았다.

## 1. Outbox와 TX C 로컬 원자성

### 선택

- [ADR 0063](../adr/0063-record-order-confirmation-outbox-in-tx-c.md)대로 TX B·C 분리를 유지하고 TX C에 Outbox를 기록한다.
- 직접 전송 제거는 relay 단계로 옮겼다. 계획은 이 단계에서 제거하기로 돼 있었지만, relay 없이 제거하면 그 사이 Kafka 전달이 끊기고 W6 발행 테스트가 깨진다. 기록을 먼저 추가하고 relay 도입과 동시에 전송 주체를 교체한다.

### 실행 검증

실행 환경은 JDK 21, Docker 엔진 27.5.1(colima)이다. 기본 JDK 17로는 `List.getFirst()`에서 컴파일이 실패하고, Docker Desktop 엔진 29.7.2로는 Testcontainers가 연결하지 못했다.

| 검사 | 결과 |
| --- | --- |
| 연결 전 `OrderConfirmationOutboxAtomicityTest` | 결과 XML 9건 중 6건 실패. 비성공 3건은 통과, 나머지는 Outbox 행 없음 등 의도한 실패 |
| 연결 후 같은 테스트 | 9건 통과 |
| 기록을 임시로 `REQUIRES_NEW`로 변경 | insert 뒤 TX 실패 시나리오 1건이 `expected: 0 but was: 1`로 실패. 변경은 되돌림 |
| `:apps:commerce-api:check` | 테스트 559건 통과, 실패·오류·skip 0. Checkstyle 위반 0. `LayeredArchitectureTest` 포함 |

첫 번째 `REQUIRES_NEW` 실험은 TX 안의 행 수 확인에서 실패해 rollback 뒤 잔존을 증명하지 못했다. 그 확인을 제거한 뒤 다시 실행한 결과가 위 표의 값이다.

### 검증 범위와 한계

- 직렬화 실패는 `ObjectMapper` spy로, insert 실패는 같은 eventId 행을 먼저 저장해 실제 유일 제약으로 만들었다.
- 실제 프로세스 강제 종료는 수행하지 않았다.
- relay·broker 전송은 이 단계의 검증 범위가 아니다. `:apps:commerce-streamer:check`는 변경이 없어 실행하지 않았다.

## 2. 단일 relay와 직접 전송 교체

### 선택

- [ADR 0064](../adr/0064-deliver-order-events-with-single-polling-relay.md)대로 relay가 유일한 전송 주체다. 사용자가 설정을 모두 기본으로 두기로 해서 `kafka.yml`과 스케줄러 설정을 바꾸지 않았다.
- 처음에는 relay 전용 String serializer로 구현했다가 기본 구성에 맞춰 제거하고, 저장 JSON을 `JsonNode`로 읽어 공통 `JsonSerializer`로 보낸다.
- W6 재현은 `b0d84b4` revision 실행물로 하기로 정했고, 복구 검증은 아직 수행하지 않았다.

### 대체한 테스트

| 제거 | 검증 의도를 옮긴 곳 |
| --- | --- |
| `OrderConfirmationEventIntegrationTest` | 커밋 경계·중복·비성공·동시 적용은 `OrderConfirmationOutboxAtomicityTest`, 전송 실패 뒤 주문 유지는 `OrderOutboxRelayTest` |
| `OrderConfirmedPublicationTest` | wire 내용은 Atomicity의 payload 비교와 relay의 저장값 일치, 재전송은 중단 재발행, send 전 중단은 새 relay 전송 시나리오 |
| `W6DeliveryCasesPublishTest` | 업무 경로에서 제거. `b0d84b4`에 보존. `W6DeliveryCasesConsumeTest`는 남겨 두고 안내 문구만 그 revision으로 바꿈 |

`KafkaOrderConfirmedPublisherTest`는 broker 확인까지 대기하는 새 계약으로 다시 작성했다.

### 실행 검증

실행 환경은 1단계와 같다.

| 검사 | 결과 |
| --- | --- |
| `OrderOutboxRelayTest` | 7건 통과. 실제 MySQL·Kafka |
| `KafkaOrderConfirmedPublisherTest` | 4건 통과 |
| 전송 전에 PUBLISHED를 저장하도록 임시 변경 | `OrderOutboxRelayTest` 2건 실패(중단 재발행, 전송 실패 재시도). 변경은 되돌림 |
| `:apps:commerce-api:check` | 테스트 559건 통과, 실패·오류·skip 0. Checkstyle 위반 0. `LayeredArchitectureTest` 포함 |

### 검증 범위와 한계

- 전송 실패는 publisher spy, PUBLISHED 저장 전 중단은 `markPublished` spy로 만들었다. broker 실제 장애나 프로세스 강제 종료가 아니다.
- 재시작은 메모리 상태 없는 새 relay 객체로 표현했고 같은 DB·broker를 유지했다.
- Kafka record는 테스트 consumer로 읽었다. 실제 구매 활동 consumer 수렴은 3절에서 검증했다.
- `:apps:commerce-streamer:check`는 실행하지 않았다. 변경은 `W6DeliveryCasesConsumeTest`의 안내 문구뿐이다.

## 3. 주문 API부터 구매 활동까지 E2E

### 구성

- `OutboxDeliveryE2ETest`가 한 실행에서 HTTP 주문 API, 로컬 PG fixture, 실제 MySQL, 자신이 띄운 단일 broker, 실제 `commerce-streamer` 앱을 연결한다.
- streamer는 `CommerceStreamerProcess`로 별도 JVM에서 띄운다. 같은 JVM에 올리면 `commerce-api` 전체 테스트의 component scan에 streamer 빈이 섞이기 때문이다. 제품 runtime 의존성은 없다.
- `apps/commerce-api/build.gradle.kts`의 `test` 태스크에 streamer runtime classpath를 넘기고 입력으로 등록했다. 테스트 실행 전용 변경이다.
- 테스트는 자신이 만든 streamer 프로세스·broker·PG와 해당 주문의 구매 활동 행만 정리한다. 공유 MySQL 컨테이너에는 streamer의 빈 테이블 두 개가 남는다.

### 관찰 결과

품목이 상품 1×2, 상품 2×1인 합성 주문의 실제 실행 결과다.

```text
{"orderId":1,"eventId":"order-confirmed:v1:1","outbox":{"attemptCount":2,"status":"PUBLISHED"},
 "records":["0:0","0:1"],"committedOffset":2,"completions":1,"activities":["1x2","2x1"]}
```

| 관찰 | 결과 |
| --- | --- |
| Outbox | 동일 eventId 1행, 첫 전송 뒤 PENDING, 재발행 뒤 PUBLISHED |
| Kafka | 같은 partition의 offset 0·1에 같은 eventId·같은 payload record 2개 |
| 소비 | 첫 record로 완료 1건·활동 2행 저장, committed offset이 2까지 진행 |
| 구매 활동 | 재발행 전후 행(id 포함)이 같음, 상품별 수량 2·1 유지 |
| 격리 | dead letter record 0 |
| 주문·PG | PG create 1, 외부 거래 1, 확정 시각 불변 |
| 재고·포인트 | 재고 8·9로 추가 변화 없음, 포인트 잔액 불변 |

첫 전송 뒤 Outbox가 PENDING인데 구매 활동은 이미 저장된 장면이 나온다. 발행 상태와 구매 활동 반영이 별개의 사실임을 보여 준다.

### 실행 검증

| 검사 | 결과 |
| --- | --- |
| `OutboxDeliveryE2ETest` | 1건 통과 |
| consumer의 기존 사실 판단을 임시로 항상 false로 변경 | 1건 실패(`두 번째 record까지 consumer offset 진행` 기한 초과). 변경은 되돌림, 되돌린 뒤 통과 |
| `:apps:commerce-api:check` | 테스트 560건 통과, Checkstyle 위반 0 |
| `:apps:commerce-streamer:check` | 테스트 90건 통과, Checkstyle 위반 0 |

- 위 두 `check`는 Gradle 입력 등록 전에 실행했다. 이후 변경은 그 한 줄이며 E2E만 다시 실행했다.
- 입력 등록 전의 첫 consumer 변경 실험은 Gradle이 테스트를 건너뛰어 결과가 무효였다. 이후 `timeout` 명령 부재로 한 번 더 실행되지 않았고, 위 표는 실제로 실행된 결과만 기록했다.
- consumer 수신 횟수는 애플리케이션 로그로 세지 않았다. offset 진행으로 두 record를 지나갔음을 확인했다.
- 중단은 `markPublished` hook이고 재시작은 새 relay 객체다. 프로세스 강제 종료나 복제 broker 조건은 포함하지 않는다.

## 4. W6 누락 재현과 복구

### 구성

- [ADR 0065](../adr/0065-recover-pre-outbox-orders-from-stored-facts.md)대로 저장된 사실로 Outbox를 보충하고 relay로 보낸다.
- 준비: `./scripts/build-w6-revision.sh`가 작업 트리와 분리된 `build/w6-revision`에서 `b0d84b4`를 빌드한다. 산출물에 `OrderOutbox` 클래스가 없고 `OrderConfirmationListener`가 있음을 확인했다.
- 실행: `./gradlew :apps:commerce-api:experimentTest --tests '*W6OutboxRecoveryExperimentTest'`. 테스트가 자신의 MySQL·broker·PG를 만들고 모든 확인 뒤 자신이 만든 자원만 정리한다. 중간에 DB·broker·PG를 초기화하지 않는다.
- 사용자와 상품은 합성 데이터로 시드한다. 사용자는 공개 API가 없어 `users`·`point_balance`에 직접 넣고, 브랜드·상품·주문은 API로 만든다. 주문 상태와 PG 결과는 직접 넣지 않았다.

### 재현 방식

- 주문 A: W6 앱이 닿을 수 없는 broker 주소를 쓰게 하고 실제 API로 확정한다. commit 뒤 after-commit send가 metadata를 기다리는 동안 reconcile 응답이 끝나지 않았음을 확인하고 프로세스를 SIGKILL했다.
- 주문 B: 실제 broker에 연결한 W6 앱을 다시 띄워 발행하고 실제 streamer가 소비했다.
- 이 재현은 W6 원본 행의 복원이 아니라 이번 실행에서 만든 사건이다.

### 관찰 결과

준비가 끝난 시점이다.

| 주문 | 상태 | eventId | confirmedAt | PG 거래 | Outbox | 구매 활동 |
| --- | --- | --- | --- | --- | --- | --- |
| A | CONFIRMED·CONSUMED | `order-confirmed:v1:1` | `2026-11-02T08:06:40.422137Z` | `TEST-TX-1` | 테이블 없음 | 0행 |
| B | CONFIRMED·CONSUMED | `order-confirmed:v1:2` | `2026-11-02T08:06:48.429395Z` | `TEST-TX-2` | 테이블 없음 | 1행 |

PG 기준값은 create 2, 외부 거래 2, 조회 2다.

복구 뒤 결과다.

```text
{"outbox":["order-confirmed:v1:1=PUBLISHED attempts=1","order-confirmed:v1:2=PUBLISHED attempts=1"],
 "records":["2@2:0","2@2:1","1@0:0"],"activitiesA":["1x2","2x1"],"activitiesB":["1x1"],
 "pgDuringRecovery":{"transactions":0,"lookups":0,"create":0}}
```

| 관찰 | 결과 |
| --- | --- |
| 주문 A | 빠졌던 구매 활동 2행 생성(상품 1×2, 상품 2×1) |
| 주문 B | 별도 offset으로 재발행, 내용 동일, 구매 활동 행 불변 |
| identity | Outbox의 eventId·confirmedAt·품목이 준비 단계 값과 같음 |
| 복구 중 PG | create 0, 외부 거래 0, 조회 0 증가 |
| 주문·재고 | 상태·확정 시각·재고 불변 |
| 격리 | dead letter topic 없음, 완료 기록 2건 |
| 두 번째 복구 | `added=false` 로그 확인, Outbox·Kafka record·구매 활동 불변 |

### 실행 검증

| 검사 | 결과 |
| --- | --- |
| `OrderOutboxRecoveryTest` | 3건 통과 |
| `W6OutboxRecoveryExperimentTest` | 1건 통과 |
| 메시지 eventId를 임시로 변경 | 같은 시험이 `consumer offset 끝까지 진행` 기한 초과로 실패. 변경은 되돌림 |
| `:apps:commerce-api:check` | 테스트 563건 통과, 실패·오류·skip 0. Checkstyle 위반 0. `LayeredArchitectureTest`·E2E·복구 규칙 테스트 포함. 복구 시험은 `experimentTest`라 포함되지 않음 |

`commerce-streamer` 제품 코드는 변경하지 않았고 이 단계에서 `:apps:commerce-streamer:check`를 다시 실행하지 않았다. 복구 시험에서 실제 앱으로 실행했다.

## 5. 운영 절차와 보존 정책

- [ADR 0066](../adr/0066-keep-outbox-and-dedup-records-without-automatic-deletion.md)대로 자동 삭제와 `BLOCKED`를 두지 않는다. 절차는 [운영 절차](operations.md)에 있다.
- 복구 진입점은 PUBLISHED 행을 다시 보내지 않으므로, PUBLISHED 재처리는 상태를 되돌리는 UPDATE로 정리했다.
- 재처리 UPDATE를 처음에는 `next_attempt_at = UTC_TIMESTAMP(6)`로 작성했다. Docker VM 시계가 Mac보다 6초 빨라 행이 앱 기준 미래가 되어 테스트가 실패했다. 기존 값이 이미 지난 시각이므로 시각을 바꾸지 않게 고쳤다.

| 검사 | 결과 |
| --- | --- |
| `OrderOutboxRelayTest` | 9건 통과. 위 운영 조회문·재처리 UPDATE 포함 |
| `checkstyleTest` | 위반 0 |

전체 `check`는 이 단계의 테스트 2건을 추가하기 전(563건)에 실행했다. 이 단계에서 제품 코드는 바꾸지 않았다.


## 6. 스케줄러 등록 조건과 max.block 실험 보완

### 스케줄러 등록 조건

`OrderOutboxRelayScheduler`가 `relay.scheduling-enabled`만 확인해, `commerce.order-events.enabled=false`와 `scheduling-enabled=true`가 함께 지정되면 relay 빈이 없어 앱이 기동하지 못했다. 두 설정을 모두 확인하도록 조건을 바꿨다. Outbox 기록은 계속 `enabled`와 무관하다.

`OrderOutboxRelaySchedulerConditionTest`는 실제 relay·스케줄러 클래스만 등록한 컨텍스트로 네 조합을 확인한다. 전체 앱 컨텍스트로 확인한 것은 아니다.

| enabled | scheduling-enabled | 기동 | relay | 스케줄러 |
| --- | --- | --- | --- | --- |
| false | false | 성공 | 없음 | 없음 |
| false | true | 성공 | 없음 | 없음 |
| true | false | 성공 | 있음 | 없음 |
| true | true | 성공 | 있음 | 있음 |

조건을 이전 값으로 되돌리면 `false, true` 조합만 실패했다.

### ProducerMaxBlockExperimentTest를 W7 경로로 교체

이전 실험은 AFTER_COMMIT 직접 발행에서 주문 확정 요청이 `max.block.ms`만큼 묶이는지 쟀다. W7에는 그 경로가 없어 기대가 현재 코드와 맞지 않았다. W6 측정 결과는 [week6 구현 기록 32절](../week6/implementation-log.md#max-block-measurement)에 당시 결과로 남아 있고, W6 코드는 `b0d84b4`에서 실행할 수 있다.

같은 조건(브로커 종료, metadata 없는 새 producer)에서 W7은 대기가 어디로 옮겨졌는지 잰다. 실험은 `experiment` tag라 일반 `check`에서 제외된다.

| 관찰 | 결과 |
| --- | --- |
| 주문 확정(`reconcile`) | 104ms, 주문 CONFIRMED, Outbox PENDING·attempt 0 |
| relay 한 회차 | 5,260ms, 시작 1초 뒤 사용 중 커넥션 최댓값 0(풀 10) |
| relay 뒤 Outbox | PENDING, attempt 1, lastError `Send failed` |

커넥션 0은 50ms 간격 표본에서 본 값이다. relay가 TX를 열어 두도록 바꾼 변형으로 이 관찰을 검증하지는 않았다.

### 실행 검증

| 검사 | 결과 |
| --- | --- |
| `:apps:commerce-api:check --rerun-tasks` | 569건 통과, skip 0. Checkstyle main·test 위반 0, `LayeredArchitectureTest` 포함 |
| `:apps:commerce-streamer:check --rerun-tasks` | 첫 실행에서 `KafkaReplicationFailureExperimentTest` 1건이 컨테이너 시작 단계의 포트 바인딩 오류(`address already in use`)로 실패. 단독 재실행에서 90건 통과, skip 0, Checkstyle 위반 0 |
| `experimentTest --tests '*ProducerMaxBlockExperimentTest'` | 1건 통과 |

`W6OutboxRecoveryExperimentTest`는 이번에 다시 실행하지 않았다(4절의 결과가 마지막 실행이다).

## 7. 저장 payload를 문자열 그대로 전송

2절에서는 Kafka 설정을 바꾸지 않으려고 저장 JSON을 `JsonNode`로 읽어 공통 `JsonSerializer`로 보냈다. 기본값은 먼저 구현하려고 둔 것이었고, 이후 저장 문자열을 그대로 보내도록 바꿨다. 아직 커밋 전인 [ADR 0064](../adr/0064-deliver-order-events-with-single-polling-relay.md)의 결정·대안·비용을 이 선택으로 고쳤다.

- commerce-api의 `application.yml`만 producer 값 직렬화를 `StringSerializer`로 덮어쓴다. 공통 `kafka.yml`은 그대로다.
- `KafkaOrderConfirmedPublisher`에서 `ObjectMapper`와 JSON 파싱 실패 분기를 제거했다. relay는 payload 내용을 검사하지 않고, 읽을 수 없는 payload는 consumer의 형식 오류 처리(DLT)에 맡긴다.
- 덮어쓰는 값을 처음에는 `application.yml`의 첫 문서에 두었는데 적용되지 않았다. import한 `kafka.yml`이 그 문서보다 우선하기 때문이다. 뒤 문서로 옮겼다.

| 순서 | 결과 |
| --- | --- |
| 테스트 먼저 변경 | `OrderEventProducerConfigurationTest` 2건(local·test) 실패. publisher만 바꾼 상태에서 `OrderOutboxRelayTest`의 저장값 전송 검사가 따옴표로 감싼 record 값 때문에 실패 |
| 덮어쓰기를 첫 문서에 둠 | 위 실패가 그대로 남음 |
| 뒤 문서로 옮김 | `OrderEventProducerConfigurationTest` 2, `KafkaOrderConfirmedPublisherTest` 4, `OrderOutboxRelayTest` 9, `OutboxDeliveryE2ETest` 1, `OrderOutboxRecoveryTest` 3건 통과 |

읽을 수 없는 payload가 relay를 지나 consumer의 DLT로 가는 경로는 publisher 단위 테스트(그대로 전송)와 W6 consumer의 형식 오류 테스트를 각각 확인한 것이다. 두 앱을 이어 한 번에 실행하지는 않았다.

| 전체 검사 | 결과 |
| --- | --- |
| `:apps:commerce-api:check` | 571건 통과, skip 0, Checkstyle main·test 위반 0 |
| `:apps:commerce-streamer:check` | streamer 코드 변경이 없어 test가 `UP-TO-DATE`로 다시 실행되지 않음. 마지막 실행은 6절의 90건 통과 |

## 8. 공유 JSON 계약 테스트와 IDL 학습 테스트

7절 이후 relay가 payload를 검사하지 않으므로, 메시지 모양이 두 앱 사이에서 맞는지는 테스트로만 확인된다. 기존에는 api와 streamer 테스트가 각자 JSON 문자열을 따로 가져서 한쪽만 바뀌어도 양쪽이 통과할 수 있었다.

- 공유 예시: `modules/kafka/src/testFixtures/resources/contracts/order-confirmed-v1.json`, 읽기 도구 `OrderEventContract`. 두 앱이 이미 `testFixtures(project(":modules:kafka"))`에 의존한다.
- producer: `OrderConfirmedProducerContractTest`가 앱과 같은 Jackson 자동 설정·`JacksonConfig`의 ObjectMapper로 `JsonOrderOutboxRecorder`가 저장하는 payload를 만들고 예시와 JSON 트리로 비교한다.
- consumer: `OrderConfirmedConsumerContractTest`가 `OrderConfirmedMessageParser`로 예시를 읽는다.
- IDL은 제품에 넣지 않았다. `ProtobufFieldNumberLearningTest`(modules/kafka)가 protobuf의 필드 번호 호환성을 보여 준다. 코드 생성 플러그인 대신 descriptor를 코드로 만들어 `DynamicMessage`로 읽고 쓴다. 이 테스트만을 위해 `protobuf-java`(4.26.1, `gradle.properties`의 `protobufJavaVersion`)를 modules/kafka의 test 의존성에 추가했다.

| 검사 | 결과 |
| --- | --- |
| 두 계약 테스트 | 각 1건 통과 |
| producer 메시지의 `confirmedAt`을 임시로 `confirmedTime`으로 변경 | producer 계약 테스트만 실패, consumer 계약 테스트 통과. 되돌림 |
| consumer parser가 임시로 `qty`를 읽게 변경 | consumer 계약 테스트만 실패. 되돌림 |
| `ProtobufFieldNumberLearningTest` | 3건 통과. 이름만 바꾼 필드는 값이 그대로 읽힘, 추가 필드는 옛 reader가 건너뛰고 보관, 같은 번호를 int32에서 string으로 바꾼 경우 예외 없이 기본값 0으로 읽힘. binary wire 형식에서 이 한 가지 변경만 시험했다 |
| `:modules:kafka:check`, api·streamer `checkstyleTest` | 통과, 위반 0. modules/kafka 테스트 10건 |

## 9. 중복을 줄이려는 선택의 대가 실험

[실험 문서](outbox-experiments.md)에 예측과 결과를 남겼다. `OutboxDuplicateVersusLossExperimentTest` 2건 통과(`experimentTest`).

- 순서: PUBLISHED를 send 전에 저장하고 그 사이에 멈추면 Outbox는 PUBLISHED, 같은 eventId record 0건, 재시작한 relay가 보낸 수 0. 지금 순서는 PENDING·record 1건에서 재시작 뒤 PUBLISHED·record 2건.
- 기다림: broker를 10초 얼린 동안 send-wait 3초는 같은 eventId record 3건(attempt 3), 130초는 1건이었고 한 회차가 약 10초 붙잡혔다.
- 실험을 위해 `KafkaTestBroker`에 `pause()`·`resume()`을 추가했다. 재시작과 달리 주소가 바뀌지 않는다.
- 처음 실행에서 순서 실험이 실패했다. 두 테스트가 토픽을 공유하는데 DB 정리로 orderId가 다시 1부터 시작해 앞 테스트의 같은 eventId record까지 셌다. 각 테스트가 시작한 뒤의 offset만 세도록 고쳐 다시 실행했다.
- ADR 0064 비용의 "최대 130초까지 밀릴 수 있다"를 고쳤다. `send()`의 `max.block.ms` 대기가 future 대기 앞에 더해질 수 있다.
- 처리량: `OutboxRelayThroughputExperimentTest` 2건 통과. 현재 설정(20건, 1초)에서 초당 10건은 지연 p95 약 1.2초로 따라잡았고, 초당 40건은 처리 한계 약 16건을 넘어 30초 뒤 720건이 밀렸다(지연 최대 약 45.6초, 이후 모두 발행). 주문 API를 거치지 않고 Outbox 기록기로 합성 행을 넣었다.
- relay 설정 변경: `fixed-delay-ms` 1000 → 10000, `batch-size` 20 → 200(application.yml과 코드 기본값). 실제 트래픽이 없어 실험 3의 처리 한계 식과 가정한 허용 지연(1시간)으로 골랐다. 처리 한계는 계산값이며 이 설정으로 실험 3을 다시 실행하지 않았다. 근거는 [실험 문서](outbox-experiments.md#설정-선택-10초200건)와 ADR 0064에 있다.
- 설정 변경 뒤 `OrderOutboxRelayTest` 9, `OrderOutboxRelaySchedulerConditionTest` 4, `OutboxDeliveryE2ETest` 1, `OrderOutboxRecoveryTest` 3건과 스케줄러를 켜고 실행하는 `W6OutboxRecoveryExperimentTest` 1건이 통과했다.

## 10. 읽을 수 없는 payload의 두 앱 E2E와 마무리

7절에서 남긴 한계(읽을 수 없는 payload가 relay를 지나 consumer DLT에 도착하는 경로를 두 앱을 이어 실행하지 않음)를 `OutboxUnreadablePayloadE2ETest`로 확인했다. 정상 주문을 API·PG로 확정한 뒤 테스트가 Outbox payload를 SQL로 깨뜨렸다. 사람이 저장 payload를 잘못 고친 경우를 흉내 낸 테스트 전용 입력이다.

| 관찰 | 결과 |
| --- | --- |
| 깨진 주문의 Outbox | PUBLISHED (relay가 검사 없이 전송) |
| DLT | 1건, key·값이 원본과 같음 (오류 정보 헤더 포함) |
| 깨진 주문의 처리 완료 기록·구매 활동 | 0·0 |
| consumer group offset | 깨진 record 다음(1), 다음 주문 뒤 2 |
| 뒤이은 정상 주문 | PUBLISHED, 구매 활동 1행 |

이 장면은 “PUBLISHED인데 구매 활동이 없다”의 또 다른 원인이다. 위치는 Outbox가 아니라 DLT에서 찾는다([운영 절차](operations.md)).

함께 정리한 것:

- `OrderOutboxRelayTest`의 테스트 이름을 `sendsStoredKeyAndPayloadAsIsThenMarksPublishedOnce`로 바꿨다. 예전 이름의 Bytes는 실제 검사(문자열 비교)와 맞지 않았다.
- 계약 예시 도구 `OrderEventContract`에 v1 예시를 고치지 않는 규칙을 적었다. 형식을 바꾸면 v2 예시를 새로 만들고 consumer는 두 버전을 모두 읽는지 확인한다.
- design.md의 구현 계획 링크를 지웠다. 계획 문서는 로컬 작업 메모로 두고 커밋하지 않는다.

| 검사 | 결과 |
| --- | --- |
| `OutboxUnreadablePayloadE2ETest` | 1건 통과 |
| 이름을 바꾼 `OrderOutboxRelayTest` 저장값 전송 검사, `OrderConfirmedConsumerContractTest` | 각 1건 통과 |

## 11. 처리량 실험 측정 보완과 문서 표현 정리

- 처리량 실험: 실패하면 relay·sampler를 멈춘 뒤 DB를 정리하도록 `try/finally`와 종료 확인을 넣었다. relay가 PENDING 0을 확인한 시각, 유입 종료 순간의 PENDING, 회차별 처리 시간을 직접 기록하게 고쳤다. 다시 실행해 2건 통과, 결과는 [실험 문서](outbox-experiments.md#실험-3-처리량-지금-relay-설정은-초당-몇-건까지-따라잡나)에 첫 실행과 나눠 남겼다. 처음 다시 실행했을 때는 정리 단계의 interrupt를 sampler 오류로 다뤄 2건이 실패했고, 정상 종료로 처리하게 고쳤다.
- 문서: “장애가 끝난 뒤 최대 60초 안에 재개”를 재시도 대기값의 상한으로 고쳤다. “한 건 전송 시간”을 “행당 처리 시간”으로 바꿨다. 1시간 예산이 consumer 반영까지 포함한다는 점, 주간 범위의 반열린 구간, 조회 수의 범위(놀 때 최대·포화·대상 조회만), 재발행 횟수, protobuf 학습 테스트의 시험 범위를 좁혀 적었다. ADR 0064의 설정 근거도 “계산으로 고른 조합”으로 맞췄다.

## 12. 최종 전체 검사

6주차 병합 결과에 맞춘 뒤 `:modules:kafka:check :apps:commerce-api:check :apps:commerce-streamer:check --rerun-tasks --continue`를 실행했다. 첫 실행은 처리량 실험의 쓰지 않는 import 한 줄 때문에 `commerce-api`의 test Checkstyle에서 실패했고, 그 import를 지운 뒤 다시 실행했다.

| 대상 | 결과 |
| --- | --- |
| `modules:kafka` | 테스트 10건 통과, skip 0, Checkstyle main·test·testFixtures 위반 0 |
| `commerce-api` | 테스트 573건 통과, skip 0, `LayeredArchitectureTest` 포함, Checkstyle main·test 위반 0 |
| `commerce-streamer` | 테스트 91건 통과, skip 0, Checkstyle main·test 위반 0 |

`BUILD SUCCESSFUL`(12분 50초)로 끝났다. `experiment` 태그의 실험은 일반 `check`에 포함되지 않으며 각 절에 적은 실행 결과가 마지막 실행이다.

이후 보완: `OutboxUnreadablePayloadE2ETest`가 다음 주문의 구매 활동을 기다린 직후 offset 2를 바로 검사하던 것을 offset도 따로 기다리게 고쳤다(DB 저장과 offset commit은 다른 시점이다). `operations.md`와 ADR 0066의 “최대 60초 간격”을 다음 시도 시각까지의 대기 상한으로 고쳐 적었다.

## 13. Kafka 흐름의 장애 상황 실험과 전송 실패 원인 기록

`KafkaFailureScenariosExperimentTest`로 몰림·잘못된 메시지·consumer 강제 종료·broker 멈춤·보낼 수 없는 메시지를 실제 구매 활동 앱까지 이어 관찰했다. 결과는 [실험 4](outbox-experiments.md#실험-4-kafka-흐름의-장애-상황)에 있다.

- 첫 실행은 Docker 연결이 끊겨 테스트 클래스 초기화에서 실패했다. 연결을 다시 연 뒤 5건을 실행했고 4건이 통과했다.
- 실패한 1건(S5)은 동작이 아니라 기록의 문제였다. 크기 초과로 막힌 행의 `last_error`가 `Send failed`뿐이었다. `KafkaOrderConfirmedPublisher`는 future의 실패만 감싸고, `send()` 호출에서 바로 던진 실패는 그대로 relay로 올려 eventId와 원인이 빠졌다. 6절의 broker 장애 실험에서 `last_error`가 `Send failed`였던 것도 같은 이유다.
- publisher가 바로 던진 실패도 감싸고, 감싼 예외를 벗겨 가장 안쪽 원인을 남기도록 고쳤다. 먼저 단위 테스트(`immediateSendFailureIsReportedWithEventIdAndRootCause`)를 추가해 실패를 확인했다.

| 검사 | 결과 |
| --- | --- |
| `KafkaOrderConfirmedPublisherTest` | 5건 통과 |
| `OrderOutboxRelayTest` | 9건 통과 |
| S5 다시 실행 | 1건 통과, `last_error`에 eventId와 `RecordTooLargeException` 원인이 남음 |
| `commerce-api` Checkstyle main·test | 위반 0 |

나머지 S1~S4는 publisher 수정 전에 실행한 결과다. 수정은 실패 메시지만 바꾸므로 그 결과에 영향을 주지 않는다고 보고 다시 실행하지 않았다.

## 14. 장애 상황 확장과 Outbox 필요성 비교

[실험 4](outbox-experiments.md#실험-4-kafka-흐름의-장애-상황)에 DB 멈춤(S6), 대량 재처리 실수(S7), consumer 추가(S8), relay 중복 실행(S9), 재시도 예산보다 긴 DB 장애(S10)를 더했고, [실험 5](outbox-experiments.md#실험-5-같은-사고-outbox가-있을-때와-없을-때)로 같은 사고를 6주차·7주차 앱에 일으켜 비교했다.

- 테스트 도구: `MySqlTestContainersConfig`에 `pause()`·`resume()`, `KafkaTestBroker`에 `stopProcess()`·`startAgain()`, `SpringAppProcess`에 6주차 앱·구매 활동 앱으로 추가 인자를 넘기는 오버로드를 더했다.
- 실험의 relay 반복은 회차가 예외로 끝나도 기록만 하고 다음 회차로 넘어가게 바꿨다. 제품 스케줄러의 동작과 맞춘 것이다.
- S1~S9를 한 번에 실행해 9건 통과했다. S10은 따로 실행해 1건 통과했다.
- 실험 5의 첫 실행은 결제 요청 키가 실행마다 겹쳐 3건 모두 실패했다(앱이 다른 주문에 쓰인 키를 거절). 주문마다 다른 키를 쓰게 고쳤다.
- 두 번째 실행은 broker를 얼리는(pause) 방식이었고, 6주차도 사건을 잃지 않아 O1·O2가 실패했다. 얼린 broker가 받은 요청을 들고 있다가 깨어난 뒤 기록했다. 이 결과는 실험 5에 따로 남겼고, broker 프로세스를 내리는 방식으로 다시 실행해 3건 통과했다(6주차 0건, 7주차 3건).

## 15. 직렬 relay 상한 측정

세일처럼 초당 수백 건이 들어오는 경우를 보기 위해, batch를 아주 크게 해도 relay가 넘지 못하는 상한을 쟀다. 결과는 [실험 3](outbox-experiments.md#batch를-키워도-넘지-못하는-상한)에 남겼다.

- `OutboxRelayThroughputExperimentTest`에 `serialRelayStopsAtPerRowCeilingEvenWithHugeBatch`(초당 200건, batch 5,000, 간격 1초)를 추가했다. 두 경우가 같은 측정 메서드를 쓰도록 나눴다.
- PENDING 수는 엔티티 전체 조회 대신 `COUNT(*)`로 세게 바꿨다. 수천 행을 매초 읽으면 측정 자체가 DB에 부하를 준다. 기존 두 경우(초당 10·40건)는 이 변경 뒤 다시 실행하지 않았다.
- 새 경우만 실행해 1건 통과했다. 6,000건 모두 발행, 처리율 초당 약 84.7건, 행당 처리 시간 약 11.1ms였다.
- 제품 설정(10초·200건)은 바꾸지 않았다.

## 16. relay 구조 비교

직렬 relay의 상한을 넘으려면 무엇을 바꿔야 하는지 데이터로 보기 위해, 행당 시간을 나누고 후보 구조를 비교했다. 결과는 [실험 3](outbox-experiments.md#relay-구조-비교--행당-시간은-어디에-쓰이나)에 남겼다.

- `OutboxRelayStructureExperimentTest`를 추가했다. 지금 relay, 보내기만 모으기, 보내기 모으기 + DB 갱신 묶기, 직렬 relay 3개 병렬을 같은 조건(PENDING 3,000건 비우기, batch 500, 워밍업 1번, 측정 3번)에서 비교한다. 후보 구조는 실험 코드 안에서 성공 경로만 만들었고 제품 relay는 바꾸지 않았다.
- 지금 relay에는 publish 호출 시간만 더하는 래퍼를 끼워, 행당 시간을 보내기와 나머지(DB TX·회차 조회)로 나눴다.
- 같은 비교를 SQL 로그를 켠 테스트 프로필 기본값으로 재는 하위 클래스 `OutboxRelayStructureWithSqlLogExperimentTest`를 추가했다. 두 클래스가 broker를 같이 쓰므로 클래스가 끝날 때 broker를 닫지 않고, topic은 없을 때만 만든다.
- 두 클래스를 함께 실행해 8건 통과했다(약 22분). 지금 relay는 초당 약 100건이었고 행당 약 10.0ms 중 보내기가 약 1.2ms, 나머지가 약 8.8ms였다. 보내기만 모으면 약 124건, DB 갱신까지 묶으면 약 13,300건, 3개 병렬은 약 230건이었다.
- `commerce-api` test Checkstyle 통과.

## 17. 실패 처리를 갖춘 relay 후보 비교

“DB 갱신 묶기” 후보에 실패 처리를 넣어 지금 relay와 다시 비교했다. 결과는 [실험 3](outbox-experiments.md#실패-처리를-갖춘-후보와-다시-비교)에 남겼다. 제품 relay는 바꾸지 않았다.

- 테스트 코드에 후보 `BatchedOutboxRelay`를 만들었다. 시도 기록·PUBLISHED를 UPDATE 한 번씩으로 묶고, 보내기는 모아서 응답을 기다린다. 실패한 행만 `OrderOutboxTransactions.scheduleRetry`로 남기고, 바로 던진 send 실패도 모은다. 오류 형식은 제품 publisher와 같다.
- `OutboxBatchedRelayExperimentTest`로 평소 처리량, 크기 초과 행 섞임, 보낸 뒤 기록 전 멈춤, broker 10초 멈춤을 두 relay에 같은 조건으로 실행했다.
- 첫 실행은 8건 통과했지만 broker 멈춤의 후보 결과는 쓸 수 없었다. 후보가 멈추기 전에 다 비웠기 때문이다. broker를 먼저 멈춘 뒤 비우기를 시작하도록 고쳐 그 2건을 다시 실행했고 통과했다.
- 주요 결과: 평소 초당 약 112건 대 약 15,200건, 막힌 행이 섞일 때 정상 행이 다 나갈 때까지 약 31.3초 대 약 0.8초, 보낸 뒤 기록 전 멈춤에서 다시 나간 건수 1건 대 500건, broker 10초 멈춤 뒤 비우기 약 43.8초 대 약 10.3초. 중복은 멈춤 상황 외에는 없었다.
- `commerce-api` test Checkstyle 통과.

## 18. 전체 경로 부하 실험

relay만 떼어 잰 처리량이 전체 경로에서도 맞는지, 주문 API부터 구매 활동 저장까지 부하를 주고 확인했다. 결과는 [실험 3](outbox-experiments.md#전체-경로-부하--relay가-정말-먼저-막히나)에 남겼다.

- k6 시나리오 `scripts/week7-order-flow.k6.js`를 추가했다. 흐름마다 주문 생성 → 결제 확정 → PG callback을 보내고 한 줄씩 기록한다.
- `FakePaymentServer.settleCreatedAs(status)`를 추가했다. 생성 응답은 PENDING으로 주고 저장한 거래는 지정한 결과로 둔다. 실제 PG처럼 결과를 조회·callback으로만 알 수 있게 해, 부하 중 거래마다 상태를 바꾸지 않아도 된다.
- `OrderFlowLoadExperimentTest`를 추가했다. 별도 프로세스의 주문 API(relay 켬, 연결 풀 40)와 구매 활동 앱을 띄우고, 도착률을 단계별로 올리며 DB를 1초마다 읽는다. relay 설정 두 가지(10초·200건, 1초·1,000건)를 비교한다.
- 첫 실행은 컨테이너 포트 충돌로 시작 전에 실패했다. 두 번째 실행은 앞 설정이 남긴 PENDING 때문에 1초·1,000건의 측정이 섞였다. 설정마다 시작 전에 남은 PENDING을 지우도록 고쳐 세 번째로 실행했고, 2건 통과했다(약 11분).
- 결과: 두 설정 모두 relay가 먼저 막혔다(제품 설정 발행 초당 약 13건, 1초·1,000건은 초당 80건 단계에서 약 24건). 주문 API는 이 환경에서 확정 초당 약 85~110건에서 포화됐고, 그때 relay 발행은 초당 2건 안팎으로 떨어졌다. 구매 활동 중복은 0이었다.
- `commerce-api` test Checkstyle 통과. 제품 설정은 바꾸지 않았다.

## 19. 운영 리허설

평소 트래픽 중의 장애를 운영자가 볼 수 있는 신호만으로 기록했다. 결과는 [실험 6](outbox-experiments.md#실험-6-운영-리허설--평소-트래픽-중의-broker-장애와-relay-중복-실행)에 남겼다.

- `OperationsRehearsalExperimentTest`를 추가했다. 실제 주문 API에 k6로 초당 10건을 보내는 중에 장애를 넣고, 1초마다 운영 신호를 기록한다. 멈춘 DB·broker에 붙잡히지 않도록 신호 조회에 짧은 응답 대기 한도를 둔다.
- `SpringAppProcess.logLineCount(text)`를 추가했다.
- 처음에는 장애 7종을 만들었으나, Outbox와 바로 닿는 broker 장애와 relay 중복 실행 2종만 남기고 실행했다. 뺀 5종(구매 활동 앱 강제 종료, DB 멈춤, 재시도 예산 초과, 다른 버전 메시지, 크기 초과 주문)은 실험 4에서 이미 본 장애다.
- 2건 통과(약 5분). broker 장애 동안 주문 API 실패 0, `last_error`와 send 실패 로그 0줄, 가장 오래된 PENDING이 약 70초까지 자랐고 broker가 돌아온 뒤 모두 반영됐다. relay 중복 실행은 초당 10건에서 중복이 생기지 않았다.
- `commerce-api` test Checkstyle 통과.


## 20. 실험 추가 뒤 전체 검사

13~19절에서 바꾼 테스트 도구(`FakePaymentServer`, `SpringAppProcess`, `KafkaTestBroker`, `MySqlTestContainersConfig`)와 실험 코드가 일반 검사에 영향을 주는지 `:modules:kafka:check :apps:commerce-api:check :apps:commerce-streamer:check --rerun-tasks --continue`로 확인했다.

| 대상 | 결과 |
| --- | --- |
| `modules:kafka` | 테스트 10건 통과, skip 0 |
| `commerce-api` | 테스트 574건 통과, skip 0, `LayeredArchitectureTest` 포함 |
| `commerce-streamer` | 테스트 91건 통과, skip 0 |
| Checkstyle | 세 모듈 모두 위반 0 |

`BUILD SUCCESSFUL`(11분 18초). 12절보다 1건 늘어난 것은 13절의 publisher 단위 테스트다. `experiment` 태그의 실험은 포함되지 않으며 각 절의 실행 결과가 마지막 실행이다.
