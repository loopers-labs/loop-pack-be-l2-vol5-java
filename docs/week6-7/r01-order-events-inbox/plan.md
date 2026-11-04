# R01 상세 설계 · 주문 확정 이벤트와 Consumer Inbox

2026-11-04 (Asia/Seoul). 현재 단계는 **설계이며 업무 코드는 미구현**이다.
기준 브랜치는 `volume-6-7/r01-order-events-inbox`, 출발 준비 커밋은 `00c2069`다.
[통합 요구사항](../total_requirement.md)과 사용자의 Inbox 추가 조건을 따른다.

- [이벤트 계약](event-contract.md): wire 형식·검증·동등성·최초 확정 시각
- [Inbox 저장 설계](persistence.md): 테이블·원자성·동시 중복·충돌
- [검증 계획](verification.md): 기능별 검사와 W6 재현 자료

## 1. 기존 코드에서 확인한 사실

| 실제 코드 | 설계에 미치는 영향 |
|---|---|
| [ApplyOrderPaymentService](../../../apps/commerce-api/src/main/java/com/loopers/application/ordering/service/ApplyOrderPaymentService.java)의 `apply` | 이미 독립 업무 TX인 REQUIRES_NEW다. 이 TX 안에 최초 성공 사건 생성을 연결한다 |
| 같은 서비스의 `completed`와 [ApplyOrderPaymentResult](../../../apps/commerce-api/src/main/java/com/loopers/application/ordering/result/ApplyOrderPaymentResult.java) | APPLIED에는 결제 실패 반영도 포함된다. APPLIED 반환만으로 성공 이벤트를 발행하면 안 된다 |
| [OrderRepositoryImpl.completePgIfPending](../../../apps/commerce-api/src/main/java/com/loopers/infrastructure/persistence/ordering/repository/OrderRepositoryImpl.java) | 주문 CAS 성공 뒤 기록까지 saveAndFlush한다. 서비스가 들고 있던 원래 Order 객체는 자동 갱신되지 않는다 |
| [OrderRecordJpaEntity](../../../apps/commerce-api/src/main/java/com/loopers/infrastructure/persistence/ordering/entity/OrderRecordJpaEntity.java) | 최초 확정 기록 createdAt이 이미 있다. 현재는 PrePersist에서 Instant.now로 정한다 |
| [기존 SQL](../../../docker/payment-lab/000-commerce-baseline.sql)의 order_records.created_at | datetime(6)이므로 Java 나노초를 그대로 보내면 DB 재조회와 다를 수 있다 |
| [ApplyOrderPaymentFacade](../../../apps/commerce-api/src/main/java/com/loopers/application/ordering/facade/ApplyOrderPaymentFacade.java)·[RetryOrderPaymentService](../../../apps/commerce-api/src/main/java/com/loopers/application/ordering/service/RetryOrderPaymentService.java) | rollback 증거·MySQL 데드락 재시도·현재 상태 우선 응답을 유지한다. 발행 실패를 업무 rollback으로 분류하면 안 된다 |
| [공용 Jackson 설정](../../../supports/jackson/src/main/java/com/loopers/config/jackson/JacksonConfig.java) | 알 수 없는 필드 무시·단일 값을 배열로 허용하는 설정이 있다. 이벤트 전용 엄격 parser가 필요하다 |
| [KafkaConfig](../../../modules/kafka/src/main/java/com/loopers/confg/kafka/KafkaConfig.java) | 전역 component scan에 들어오는 batch/MANUAL/concurrency 3 구성이다. API에 의존성을 추가할 때 격리가 필요하다 |
| [Kafka YAML](../../../modules/kafka/src/main/resources/kafka.yml)·[streamer YAML](../../../apps/commerce-streamer/src/main/resources/application.yml) | deserializer 오기·자동 commit 실효 값·admin 주소·앱 이름/포트를 보완해야 한다 |

원문의 '예약 소비'는 기존 StockAdjustment.DEDUCTED → CONSUMED 전이다. 상품 재고를 새로 차감하는 작업이 아니다.

## 2. 설계 선택

| 항목 | 선택 |
|---|---|
| 공유 계약 | 새 `contracts:commerce-events`의 순수 Java v1 record |
| 사건 ID | `order-confirmed:v1:{orderId}`로 안정적 유도. 전송용 UUID/시각을 사용하지 않음 |
| confirmedAt | PG 성공을 내부 확정한 order_records.created_at, UTC·마이크로초 정밀도 |
| R01 발행 | 공통 업무 TX에서 immutable 사건 발행 등록 → AFTER_COMMIT 어댑터에서 Kafka send |
| 소비 사실 소유 | streamer의 기존 `ordering` context에 Inbox·상품 구매 활동 배치 |
| Inbox 상태 | commit된 행은 처리 완료. RECEIVED/PROCESSING 선행 저장 없음 |
| 사실 비교 | 정규화 snapshot의 필드 비교. fingerprint는 필수로 추가하지 않음 |
| 동시 중복 | named DB 유일 제약, 실패 TX rollback 뒤 새 TX에서 기존 사실 검증 |
| Listener | 전용 record factory, MANUAL_IMMEDIATE, concurrency 1, DB 완료 후 같은 스레드에서 ack |
| 오류 | 소비 중단·원인 노출·복구 후 같은 group 재시작. skip/DLQ 자동 완료 없음 |

이 선택은 현재 요구사항을 구현하기 위한 설계 기준이다. 변경 시 이 문서와 관련 검사 기대값을 함께 갱신한다.

## 3. 모듈과 책임

```text
contracts:commerce-events  ← commerce-api
                          ← commerce-streamer

commerce-api      → modules:kafka / modules:jpa
commerce-streamer → modules:kafka / modules:jpa
```

두 앱 사이 runtime 의존성은 만들지 않는다. `modules:kafka`에는 topic/key 전송 기술만 두고 주문 계약을 넣지 않는다.

### 공통 계약

`contracts:commerce-events`는 OrderConfirmedV1·ItemV1, 고정 type/version과 계약 정규화 규칙을 소유한다.
패키지는 `com.loopers.contracts.ordering`이며 Spring·JPA·Jackson·앱 domain에 의존하지 않는다.
JSON codec은 각 앱의 경계 어댑터가 담당한다. collection은 방어 복사하며 계약 객체는 불변이다.

현재 root Gradle은 모든 subproject에 Boot starter를 추가한다. 구현 시 contracts 그룹은 그 적용에서 제외하고
Java 21/java-library와 테스트에 필요한 설정만 연결한다. 테스트 버전은 기존 Boot BOM을 test platform으로 재사용할 수 있다.
실제 runtimeClasspath에 framework 의존성이 없는지도 검사한다. apps/modules/supports의 기존 설정은 유지한다.

독립 DTO 두 벌은 새 모듈 변경을 줄이지만 producer/consumer 계약 드리프트를 늘린다.
이번에는 같은 저장소에서 함께 변경하는 v1 계약 하나이므로 공통 계약을 선택한다.
공유 DTO가 있다고 wire JSON·직렬화·호환성 검사를 생략하지 않는다.

### API 측

| 위치 | 책임 |
|---|---|
| application.ordering.service.ApplyOrderPaymentService | 기존 최초 성공 확정 TX와 사건 등록 |
| application.ordering.event.OrderConfirmationFactory | 저장된 CONFIRMED·PG 기록과 품목에서 v1 snapshot 생성 |
| application.ordering.gateway.OrderConfirmationPublisher | CompletionStage 기반 전송 포트. 결과는 topic/partition/offset |
| infrastructure.messaging.ordering.OrderConfirmedPublicationListener | AFTER_COMMIT 수신, 전송 호출·성공/실패 관찰 |
| infrastructure.messaging.ordering.KafkaOrderConfirmationPublisher | 전용 String/byte[] template, JSON bytes와 broker future 연결 |

application은 infrastructure 구현을 참조하지 않는다. domain은 wire record·Spring을 참조하지 않는다.

### Streamer 측

| 위치 | 책임 |
|---|---|
| interfaces.consumer.ordering.OrderConfirmedKafkaConsumer | record 수신·parser/UseCase 연결·완료 후 ack |
| interfaces.consumer.ordering.OrderConfirmedMessageParser | 엄격한 wire 검증, Command 변환. Kafka 헤더의 Java 타입을 신뢰하지 않음 |
| application.ordering.command.ConsumeOrderConfirmationCommand | 고정 사실만 전달. offset·수신 시각을 사건 identity에 넣지 않음 |
| application.ordering.usecase.ConsumeOrderConfirmationUseCase / facade.ConsumeOrderConfirmationFacade | 비트랜잭션 조율, 중복 경합 rollback 이후 검증 |
| application.ordering.service.RecordPurchaseActivityService | Inbox와 모든 품목의 단일 쓰기 TX |
| application.ordering.service.VerifyProcessedOrderConfirmationService | 경합 패배 뒤 새 readOnly TX에서 기존 완료 사실 확인 |
| domain.ordering.model | OrderConfirmation, OrderConfirmationInbox, ProductPurchaseActivity와 순수 규칙 |
| domain.ordering.repository | Inbox·구매 활동 저장 계약 |
| infrastructure.persistence.ordering.{entity,jpa,repository} | Entity·Mapper·JPA 구현, 제약 위반의 제한적 분류 |

Inbox는 구매 활동 소비 전용이다. 미래의 다른 consumer가 같은 Inbox를 공유해 이미 처리됐다고 건너뛰게 하지 않는다.
다른 소비 업무를 추가하면 별도 Inbox 또는 명시적인 consumer scope가 필요하다.

## 4. 최초 확정과 발행 경계

```text
기존 PG 호출/검증·관찰 저장은 유지
  ↓
ApplyOrderPaymentService.apply [기존 업무 TX]
  기존 시도 잠금·상태 검사
  최초 SUCCESS인지 확인
  Clock으로 UTC·마이크로초 시각 결정
  주문 CAS + PG OrderRecord 저장/flush
  StockAdjustment 소비 + task APPLIED
  주문을 저장소에서 다시 읽어 고정 snapshot 구성
  ApplicationEventPublisher로 사건 등록
  commit
  ↓
AFTER_COMMIT listener
  저장된 snapshot만 사용 → producer future 관찰
```

- 사건 등록은 최초 성공 CAS 승자 경로의 모든 업무 변경 이후에 둔다.
- completed 조기 반환·CAS 실패·PENDING/UNKNOWN·확정 실패에서는 사건을 등록하지 않는다.
- TX 뒤늦은 실패/rollback이면 등록한 사건도 AFTER_COMMIT으로 전달되지 않아야 한다.
- 새 Clock은 확정 사건 시각에만 사용한다. 기존 PG 재시도 시간 체계를 함께 리팩터링하지 않는다.
- 기존 `paidByPg`에 시각을 명시하는 생성 경로와 Mapper 반영을 추가한다. POINT의 기존 생성/조회는 보존한다.
- after-commit에서 lazy entity를 읽거나 DB로 완료 표식을 쓰지 않는다.
- 발행 동기 예외/future 실패는 발행 결과로 노출하며 결제 상태·복구 작업·PG create에 전파하지 않는다.
- send 호출은 metadata 준비로 잠깐 대기할 수 있다. R01은 이 응답 지연과 누락 구간을 허용·관찰한다.
- @Async 메모리 queue를 영속 재시도 수단으로 추가하지 않는다.

`AFTER_COMMIT`에서는 commit 이후에도 원래 트랜잭션 자원이 보일 수 있다.
따라서 active 플래그 false만으로 경계를 검증하지 않고, 별도 TX/새 조회에서 확정 사실을 확인한다.
근거: [Spring 6.2 TransactionalEventListener](https://docs.spring.io/spring-framework/docs/6.2.x/javadoc-api/org/springframework/transaction/event/TransactionalEventListener.html).

## 5. Kafka 연결과 실행 설정

전용 topic은 `order-events`, group은 `purchase-activity`다. 로컬/실험 기본 topic은 2 partitions·replication factor 1이다.
이미 존재하는 topic을 임의로 재생성하지 않는다. 테스트는 고유 이름과 실제 assignment를 사용한다.

| 설정 | R01 설계 값 |
|---|---|
| producer key/value | StringSerializer / ByteArraySerializer, 전용 이름/Qualifier의 factory·template |
| producer 보장 | acks=all, enable.idempotence=true, retries=3, max.in.flight.requests.per.connection=5 |
| producer 제한 | max.block.ms=5000, request.timeout.ms=5000, delivery.timeout.ms=10000, linger.ms=0 |
| consumer key/value | StringDeserializer / ByteArrayDeserializer |
| 실효 enable.auto.commit | false를 전용 ConsumerConfig에 명시 |
| listener | record, MANUAL_IMMEDIATE, concurrency=1, asyncAcks=false |
| offset commit | syncCommits=true, syncCommitTimeout=5초 |
| poll | max.poll.records=1, fetch.min.bytes=1, fetch.max.wait.ms=100, max.poll.interval.ms=120000 |
| 최초 group 시작 | auto.offset.reset=earliest. 보존 범위 밖 데이터 복원 보장은 아님 |
| 오류 | CommonContainerStoppingErrorHandler, 선택한 handler의 실제 ack/중단 결과 검사 |

값은 R01 학습용 초기값이며 처리량 최적화 결과가 아니다. producer 대기 값의 합을 절대적인 전체 HTTP 제한이라고 표현하지 않는다.
단일 broker에서 acks=all을 확인해도 다중 broker 내구성을 증명한 것은 아니다.
멱등 producer가 앱의 별도 send나 외부 DB 변경까지 한 번으로 합쳐 주지는 않는다.
설정 호환 근거: [Kafka 3.8 producer configs](https://kafka.apache.org/38/configuration/producer-configs/).

전용 factory를 사용하므로 기존 demo batch factory의 ack/concurrency를 일괄 변경하지 않는다.
Kafka transaction manager로 DB와 offset을 원자화했다고 표현하지 않는다. listener는 일반 소비이며 DB TX를 먼저 완료한다.
다만 API에서 기존 demo 구성이 component scan되는 문제는 명시적으로 차단한다.

- 기존 KafkaConfig와 DemoKafkaConsumer에 같은 `demo-kafka.enabled` 조건을 둔다. 누락 시 기존 동작(true)을 유지한다.
- API는 이 값을 false로 지정한다. streamer의 event-lab/test도 false이며 이벤트 factory는 이 조건과 독립이다.
- 공용 YAML의 value-deserializer 오기와 Boot `consumer.enable-auto-commit` 바인딩을 바로잡는다.
- local/test admin만 `kafka:9092`로 향하던 override를 제거해 root bootstrap 주소와 같게 한다.
- runtime 타입 추측을 피하고 모든 신규 factory/template 주입에 명시적인 이름을 사용한다.
- streamer 이름은 commerce-streamer, 웹 포트는 8083, 관리 포트는 8084로 정한다.
- 보존 실험은 API의 payment-lab+event-lab, streamer의 event-lab에서 validate·명시 SQL을 사용한다.
- event-lab에는 DB/Kafka 및 기존에 로드되는 Redis 설정도 명시한다. local의 ddl-auto=create와 섞지 않는다.

Spring 문서에서 manual 모드 중단 시 이미 ack한 offset은 commit될 수 있다.
실패 record와 뒤 record를 ack하지 않고 해당 partition의 실제 committed offset으로 검증한다.
근거: [3.3 listener containers](https://docs.spring.io/spring-kafka/reference/3.3/kafka/receiving-messages/message-listener-container.html),
[3.3 error handling](https://docs.spring.io/spring-kafka/reference/3.3/kafka/annotation-error-handling.html).
온라인 3.3/6.2 문서는 해당 minor의 최신 patch를 보여 주므로 구현 시 현재 BOM의 실제 버전과 관련 테스트를 최종 기준으로 삼는다.

## 6. 고객 응답과 오류 관찰

- 주문 confirm/callback/reconcile 응답·소유권 규칙은 유지한다. 결제 확정과 구매 활동 완료를 한 상태로 합치지 않는다.
- 구매 활동 조회 API나 랭킹 API는 추가하지 않는다. 결과는 DB와 구조화 로그로 확인한다.
- 발행은 eventId·orderId와 성공 topic/partition/offset 또는 안전한 오류 분류를 남긴다.
- 소비는 같은 식별자·record 위치·CREATED/ALREADY_PROCESSED/충돌/실패를 구분한다.
- parser 실패 로그는 원문 payload나 JSON 라이브러리의 입력 snippet/cause 메시지를 그대로 출력하지 않는다.
  검증된 식별자 또는 record 위치·사유 코드만 남긴다.
- 오류 record를 성공 처리하지 않는다. 소비 중단 뒤 원인 수정·같은 group 재시작을 안내한다.
- 잘못된 사실을 같은 eventId의 다른 payload로 덮어써 해결하지 않는다. 임의 skip/offset 이동은 R01 복구 절차에 없다.

## 7. 구현 순서와 다음 단계

1. 공통 v1 계약·정규화·명시 확정 시각의 작은 단위 테스트부터 작성한다.
2. Inbox·활동 domain/Entity/Repository와 실제 MySQL 원자성·경합 검사를 구현한다.
3. UseCase/Facade와 record listener·정확한 오류/ack 설정을 연결한다.
4. API의 최초 성공 사건 등록·AFTER_COMMIT producer와 PG 회귀를 연결한다.
5. 실제 배정·key·offset 재전달·listener 실패·A/B 직접 발행 재현을 수행한다.
6. W6 재현 revision·고정 입력·관찰 기록을 남기고 R02 교체 준비를 정리한다.

새 streamer 업무 코드에도 API와 같은 Checkstyle/계층/순수 domain 기준을 적용한다.
기존 API gate는 완화하지 않고, 새 검사의 실제 대상·실행 결과를 기록한다.
문서 단계에서 테스트 이름은 예정 이름이며 통과 건수는 아직 없다.

R02에서는 snapshot·Inbox·활동·consumer를 유지하고 사건의 직접 발행 등록을 같은 업무 TX의 Outbox insert로 교체한다.
Outbox만 다른 TX로 저장하지 않는다. R01에서 whole 업무 TX에 쓰인 REQUIRES_NEW와 Outbox 분리 저장 금지를 혼동하지 않는다.

## 8. 설계 자체 점검

이번 변경은 문서만이므로 저장소의 문서/빌드/테스트 전용 변경 예외에 따라 별도 기능 서브에이전트 리뷰는 실행하지 않았다.
아래는 작성자의 세 관점 대조이며 독립 리뷰 통과 기록이 아니다. 기능 구현 시 기존 세 관점 리뷰 규칙을 적용한다.

| 관점 | 확인·설계에 반영한 항목 |
|---|---|
| 비즈니스 | APPLIED와 성공 구분, PG 최초 확정만 사건 생성, 기존 예약/POINT 보존, 같은 주문 다른 사건 충돌 |
| 사용자 경험 | 발행 오류로 결제 결과 회귀 금지, 관리자 공개 API 추가 없음, 소비 실패와 결제 확정 상태 구분 |
| 기술 | DB 시각 정밀도, JPA 경합 rollback 뒤 새 TX, 기존 느슨한 JSON 설정 격리, Kafka factory/주소/ack와 테스트 자원 격리 |

업무 구현·DB migration 적용·통합 테스트·독립 리뷰 완료를 이 문서만으로 주장하지 않는다.

### 이번 문서 변경의 실제 확인

- R01 설계 4개와 연결 안내 4개의 로컬 링크 54개가 존재함을 확인했다.
- 이벤트 JSON 예시 1개를 파싱해 필수 type/version과 품목 구조를 확인했다. 실제 codec 검사는 아니다.
- 사용자 JMeter 파일은 초기 준비 때와 SHA-256이 동일하다. 문서 변경만 staging한다.
- 설계 문서의 diff 공백 오류를 확인한다. 업무 코드/빌드 설정/SQL 적용/기능 테스트 실행은 이번 변경에 없다.
