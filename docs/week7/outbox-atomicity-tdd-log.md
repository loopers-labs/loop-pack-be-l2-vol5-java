# W7 주문 확정·Outbox 공동 저장 TDD 기록

후속 2단계의 전달 구현·검사는 [단일 relay TDD 기록](outbox-relay-tdd-log.md)을 따른다. 아래는 첫 저장 증분 당시의 실행 기록으로 보존한다.

2026-11-04 첫 구현 증분. [공식 기준](grading-criteria.md) C01~03과 [TDD 계획](../week2/commerce-tdd-plan.md#w7-outbox-원자성과-복구-tdd-계획)의 첫 저장 경계를 다룬다. relay·두 중단의 재발행 E2E·W6 누락 backfill은 후속이며 이번 기록으로 완료 처리하지 않는다.

## 선택과 구현 범위

사용자는 공통 Outbox·완성 JSON 문자열, 공통 BEFORE_COMMIT listener의 같은 TX 기록을 선택했다. 이번 질문에서는 **topic도 함께 저장**하기로 선택했다. 전송 시 최신 설정을 다시 해석하면 기존 미발행 사건의 목적지가 바뀔 수 있어 저장 당시 목적지를 보존한다.

| 책임 | 실제 변경 |
| --- | --- |
| 최초 확정 판단 | 기존 `OrderPaymentStockService`의 PG SUCCESS·최초 `confirmPgPayment` 판단과 이벤트 스냅샷 생성 위치를 유지. 실행 코드·잠금 순서·트랜잭션 격리 변경 없음 |
| 공통 기록 경계 | `DomainEventListener`가 BEFORE_COMMIT에 `OutboxRecorder` 호출. `TransactionalOutboxRecorder`는 MANDATORY로 기존 TX에만 참여하며 직렬화/저장 오류를 삼키지 않음 |
| 직렬화 | `JsonOutboxMessageSerializer`가 명시적인 `OutboxEventMapping`으로 버전·원본 참조·목적지를 결정. 기존 OrderConfirmed 6개 JSON 필드·ISO 시각·품목 정규화·eventId·key 유지. 일반 로컬 이벤트는 제외 |
| 저장 | `OutboxMessage`·JPA adapter·추가 `012-outbox.sql`. 공통 `outbox_message`에 한 사건 한 행, eventId 유일 제약, 불변 topic/key/JSON/원본 참조. 기록 시각은 별도 주입 Clock이며 최초 confirmedAt을 바꾸지 않음 |
| 초기 전달 상태 | PENDING·attemptCount 0·nextAttemptAt=createdAt·publishedAt/lastError NULL. 이번 증분에는 발행 상태를 바꾸는 메서드나 relay가 없음 |
| 전송 분리 | 운영 기록 listener가 기존 Kafka publisher를 호출하지 않음. 기존 전송 enabled=false라도 Outbox 기록은 수행. 실제 저장 문자열의 Kafka 전송·future 확인은 후속 |
| W6 근거 보존 | 기존 AFTER_COMMIT 검증은 테스트 전용 `W6DirectPublicationTestConfiguration`·`W6DomainEventListener`로 보존. 운영용 우회 설정 없이 기존 W6 발행·누락·정상 소비 기대값을 유지 |

추가 DDL의 적용 순서·문자 비교·길이·인덱스·JPA validate 범위는 [스키마 안내](../week2/commerce-schema-operations.md#w7-outbox의-추가-ddl과-배포-순서)를 따른다. 수동 SQL로 사용자의 DB를 변경하지 않았다. 현재 코드의 신규 확정은 PENDING까지만 저장하므로 구매 활동으로 전달되는 W7 경로는 아직 완성되지 않았다.

## 테스트와 관찰

| 테스트 | 확인하는 결과 |
| --- | --- |
| [OutboxMessageTest](../../apps/commerce-api/src/test/java/com/loopers/domain/outbox/OutboxMessageTest.java) | 불변 사실·초기 상태·마이크로초 시각·필수값/길이/양수 버전 경계 |
| [OutboxSchemaIntegrationTest](../../apps/commerce-api/src/test/java/com/loopers/infrastructure/outbox/OutboxSchemaIntegrationTest.java) | 전용 MySQL에 실제 012 적용·Hibernate validate·JSON 원문 왕복·실제 INSERT 후 rollback·서로 다른 연결의 같은 eventId 삽입 경합·DB CHECK와 정확한 문자열 유일성 |
| [JsonOutboxMessageSerializerTest](../../apps/commerce-api/src/test/java/com/loopers/infrastructure/outbox/JsonOutboxMessageSerializerTest.java) | 기존 6필드 JSON·원본 시각·목적지 보존, 두 이벤트 종류의 명시적 mapping, 미등록/중복 mapping 거절·직렬화 오류 전파 |
| [OutboxDomainEventListenerTest](../../apps/commerce-api/src/test/java/com/loopers/application/event/OutboxDomainEventListenerTest.java) | 실제 Spring 동기화에서 commit 전에 같은 TX로 기록, 저장 오류 전파·rollbackOnly·업무 실패·TX 없음·일반 로컬 이벤트 제외 |
| [OrderOutboxAtomicityIntegrationTest](../../apps/commerce-api/src/test/java/com/loopers/application/order/OrderOutboxAtomicityIntegrationTest.java) | 실제 MySQL의 공동 저장·독립 연결 가시성·중복/동시 결과·비성공/POINT·SQL 실행 뒤 실패·외부 PG SUCCESS와 로컬 rollback 분리·기존 거래 GET 복구·직접 Kafka 호출 0 |

원자성 테스트는 전체를 테스트 자동 rollback으로 감싸지 않는다. 준비 상태를 먼저 commit하고 독립 연결에서 전후 상태를 조회한다. 정상 흐름은 같은 connection ID에서 확정·예약 소비와 Outbox INSERT를 확인하고, commit 전 다른 연결에서는 이전 PAYMENT_PENDING·Outbox 0임을 확인한다. 이후 commit된 결과는 CONFIRMED·SUCCESS·CONSUMED·Outbox PENDING 1행이다.

| 실패 경계 | 주입·실제 SQL 확인 | TX 종료 뒤 기대값 |
| --- | --- | --- |
| 직렬화 실패 | 기존 주문·시도·예약 SQL을 flush한 뒤 직렬화 경계에서 실패 | 기존 미완료 상태·HELD·재고/포인트/캐시 버전 보존, confirmedAt/paidAmount NULL, Outbox 0 |
| DB INSERT 거절 | 소유한 격리 DB에 임시 CHECK를 추가해 실제 Outbox INSERT 거절. finally에서 그 CHECK만 제거 | 동일한 전체 rollback. 원인 제거 후 같은 결과 적용 성공 |
| Outbox INSERT 후 실패 | 실제 save/flush와 TX 내부 Outbox 1행을 확인한 다음 테스트 예외 | 삽입된 Outbox까지 rollback. 기존 주문이 삭제되는 것이 아님 |
| 바깥 업무 TX 실패 | 확정 변경 SQL 뒤 commit에 도달하기 전 예외 | BEFORE_COMMIT 저장 미실행, 기존 상태 보존 |

실제 HTTP PG 복구 사례는 PG 거래를 만든 뒤 SUCCESS로 바꾸고, 결과 적용 중 Outbox INSERT 후 실패시킨다. 외부 거래 SUCCESS는 남고 로컬 상태만 복원된다. 실패 원인을 제거해 새 coordinator가 **같은 transactionKey GET**으로 재적용하면 Outbox 1행으로 수렴한다. create=1·외부 거래=1을 유지하고, 종결 뒤 반복 조회는 추가 HTTP 호출·업무 효과를 만들지 않는다. 이것은 미완료 확정 TX의 복구이며 **이미 확정된 W6 누락 backfill에서 PG 호출 0**을 검증한 것이 아니다.

## Red · Green · 검사 기록

1. 신규 모델·직렬화·listener 테스트를 먼저 작성하고 실행했다. 첫 Red는 미구현 Outbox 타입/포트로 인한 compileTestJava 실패이며 실행 assertion 실패라고 표현하지 않는다. `/private/tmp/w7-outbox-red.log`, BUILD FAILED·종료 1.
2. 최소 저장·listener 연결을 구현했다. W6 테스트 지원 코드의 Lombok test annotation processing 문제는 명시적 SLF4J logger로 수정했다.
3. 첫 실행은 62개 중 60개 통과·2개 실패였다. 실제 INSERT 후 주입한 IllegalStateException을 Spring이 DB 접근 예외로 번역해 예외 동일성 assertion이 실패했다. 전용 `InjectedOutboxFailure`로 테스트 제어용 예외를 구분했고, 실패 위치·실제 SQL·예외 전파·전체 rollback 기대값은 유지했다.
4. 수정 후 API 관련 51개 스위트·809개 테스트가 모두 통과했다. 같은 명령의 API CheckstyleTest에서 미사용 import 1개가 검출되어 명령 전체는 종료 1이었다. import만 제거하고 정적 검사를 재실행했다. 이때 앞선 명령에서 UP-TO-DATE였던 streamer 검사를 `--rerun-tasks`로 실제 재실행해 99개 통과를 확인했다. 테스트·규칙·기대값을 삭제하거나 완화하지 않았다.

```shell
./gradlew :apps:commerce-api:test \
  --tests 'com.loopers.application.order.*' \
  --tests 'com.loopers.application.event.*' \
  --tests 'com.loopers.domain.outbox.*' \
  --tests 'com.loopers.domain.order.*' \
  --tests 'com.loopers.infrastructure.outbox.*' \
  --tests 'com.loopers.infrastructure.event.*' \
  --tests 'com.loopers.interfaces.api.order.*' \
  --tests '*ArchitectureTest' \
  :apps:commerce-api:checkstyleMain :apps:commerce-api:checkstyleTest \
  :apps:commerce-streamer:check --console=plain
```

정적 검사 수정 후 실제 재실행:

```shell
./gradlew :apps:commerce-api:checkstyleMain :apps:commerce-api:checkstyleTest \
  :apps:commerce-streamer:check --rerun-tasks --console=plain
```

| 검사 | 실제 결과 |
| --- | --- |
| 신규 Outbox 검사 | 5개 스위트·56개 통과: 모델 25·수동 DDL 5·직렬화 5·listener 5·실제 원자성 16. 아래 API 809개에 포함 |
| API 영향 범위 | 51개 스위트·809개, 실패/오류/skip 모두 0. 주문·결제 API·기존 POINT·소유권·중복/경합·W6 실제 발행/소비/누락 회귀와 ArchUnit 포함 |
| streamer 실제 재실행 | 9개 스위트·99개, 실패/오류/skip 모두 0. 구매 활동 중복·공동 TX·offset 재전달·배정·ArchUnit 포함 |
| Checkstyle | 두 앱 main/test 보고서 4개, 위반 0 |
| 종료 | 재실행 BUILD SUCCESSFUL, 종료 0, 1분 1초. API 테스트 자체는 앞선 809개 통과 실행 결과를 사용 |
| 정리 | 테스트 HTTP/consumer/producer/자식 JVM 종료 경로 완료, 종료 후 docker ps에 실행 중 컨테이너 0. 사용자 DB·기존 topic/group·볼륨 삭제 없음 |

로그는 `/private/tmp/w7-outbox-regression.log`와 `/private/tmp/w7-outbox-static-consumer.log`다. 이번 검사는 API 전체 check가 아니라 위 영향 범위 테스트·정적 검사와 streamer 전체 check다. 새 relay의 실제 broker 전달과 중단 복구를 검증한 것으로 확대하지 않는다.

## 남은 범위

- 단일 relay의 실행 설정·유한 대기·batch·재시도 정책을 정하고 저장 payload를 그대로 전송한다.
- 실제 broker 성공 뒤 별도 TX의 PUBLISHED 저장, 늦은 결과 보호와 두 중단의 재개를 구현한다.
- 같은 eventId의 별도 broker record·실제 consumer 수신 중복과 구매 효과 불변을 검증한다.
- 보존한 W6 준비를 같은 자원의 W7 복구까지 이어 backfill·PG 추가 호출 0·2회 실행 불변을 검증한다.
- 이번에는 사용자 DB·기존 topic/group·볼륨을 변경하지 않았고 커밋·푸시하지 않았다. 테스트가 소유한 컨테이너·HTTP fixture·broker 자원만 종료한다.
