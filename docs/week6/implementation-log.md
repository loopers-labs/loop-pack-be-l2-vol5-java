# 6주차 구현 기록

주문 확정 이벤트 발행과 상품 구매 활동 저장의 결정·구현·검증 과정을 적는다. 현재 계약과 이유는 [설계](design.md)에 둔다.

## 현재 상태

| 항목 | 진행 상태 | 근거 |
| --- | --- | --- |
| 주문 확정 메시지의 key·eventId | key는 내부 orderId 문자열, eventId는 `order-confirmed:v1:{orderId}`로 적용 | [Kafka 연결](#kafka-producer), [설계](design.md#event-identity) |
| 최초 확정 시각 | PG 최초 SUCCESS 적용에서 nullable `orders.confirmed_at` 저장 | [준비·커밋 후 호출 구현](#after-commit-boundary) |
| 로컬 Kafka 기반 | 자동 생성 끔·offset topic 복제 3·init으로 `order-events`(3/3/2)·`order-events-dlt`(1/3/2) 생성, local profile 발행·소비 켬 | [27절](#local-topics-and-activation) |
| 발행 순서·연결 | `AFTER_COMMIT` listener·발행 인터페이스 호출 구현 | [준비·커밋 후 호출 구현](#after-commit-boundary), [설계](design.md#publish-after-commit) |
| 실제 Kafka 전송 | 구현·격리 broker 검증 | 최종 JSON·실제 record·재전송·성공 metadata·오류 관찰 구현. 기본값·test는 발행 꺼짐, local profile에서 켬([27절](#local-topics-and-activation)) |
| 구매 활동 DB 저장 | 완료 기록과 모든 품목의 원자적 저장·동일 사실 no-op·충돌·동시 경쟁 복구 구현 | [DB 멱등 처리](#idempotent-purchase-handling). 실제 listener·ack·오류 처리기 연결은 [23절](#order-listener-wiring)·[26절](#consumer-error-handler) |
| 수신 기본 준비 | parser·어댑터·조건부 주문용 record factory·@KafkaListener→DB→정상 반환→BATCH 위치 기록 연결. 기본값·test는 자동 구독 0개, local profile에서 주문 listener(concurrency 3) | [실제 listener 연결](#order-listener-wiring), 업무용 error handler [26절](#consumer-error-handler), 로컬 활성화 [27절](#local-topics-and-activation). 운영 배포별 consumer 수는 정하지 않음 |
| 주문용 ack | BATCH 채택·실제 listener 한 인자 경로·업무용 오류 처리기 연결. DB 완료 뒤 정상 반환, 위치는 container가 관리 | [ADR 0054](../adr/0054-commit-order-consumer-offsets-per-poll.md), [업무용 검증 37절](#batch-adoption). 과거 MI·비교 기록은 보존 |
| 사실 충돌의 소비 대응 | 기존 사실 유지·DLQ 분리 후 정상 주문 계속 처리. 업무용 error handler 연결·실제 listener 검증 | [ADR 0047](../adr/0047-quarantine-conflicting-order-confirmation-facts.md), [채택 기록](#conflicting-fact-adoption). 비교 결과는 21절에 보존 |
| DLQ 전송 실패의 복구 | 원본 offset 보호·listener 전체 중단·사람의 원인 해결 후 같은 group 재시작. 업무용 error handler 연결·실제 listener 검증 | [ADR 0048](../adr/0048-stop-consumption-on-dlq-publication-failure-and-resume-manually.md), [선택 기록](#dlq-failure-manual-recovery) |
| 주문 기능 검증 | DB·PG→내부 이벤트→실제 Kafka record, DB handler 멱등성·DB commit 뒤 미커밋 재전달, 두 앱을 잇는 미발행/정상 사례 | 현재 변경: streamer 전체 90건·지연 DLT experiment 1건·main/test Checkstyle·ArchUnit 통과([37절](#batch-adoption)). API 524·Kafka 모듈 7건은 [31절](#db-driver-timeouts)의 이전 실행이며 이번에 재실행하지 않음. 두 앱 사례는 [28절](#w6-delivery-cases) |
| Kafka 설정 비교·재전달 | 선택한 재시도·DLQ·3개 partition/consumer·복제 장애 조합의 격리 검증 완료. Compose 적용은 27절 | [선택한 설정 검증](#selected-settings-verification)(당시 streamer 70건·Kafka 모듈 5건). `min.insync` 1·2의 유실 비교는 [33절](#min-isr-loss-experiment). 과거 개별 실행은 각 절에 보존 |
| 주문용 error handler | 메시지 오류·충돌 DLQ, 그 밖의 오류는 1초부터 2배·최대 30초·합 5분 재시도 뒤 중단, DLQ 실패 중단 | [26절](#consumer-error-handler), 재시도 변경은 [34절](#long-consumer-retry) |
| DB 드라이버 timeout·producer 대기 | 두 앱 `connectTimeout` 3초·`socketTimeout` 10초, `max.block.ms` 5초 측정 | [31절](#db-driver-timeouts)·[32절](#max-block-measurement) |

<a id="key-choice"></a>

## 1. 주문 확정 메시지의 key 선택

**결정 단계이며 실제 주문 이벤트를 발행하거나 소비한 검증은 아직 하지 않았다.**

| 구분 | 내용 |
| --- | --- |
| 검토한 기준 | 주문 ID와 상품 ID 중 어느 업무 단위로 메시지를 묶을지 비교했다. |
| 메시지 단위 | 한 주문의 여러 상품·수량을 하나의 주문 확정 사실로 전달한다. |
| 사용자 판단 | 확정하는 대상이 주문이고 한 주문에 여러 상품이 포함되므로 주문 ID를 key로 선택했다. |
| 선택 이유 | 주문 전체의 확정 사실을 유지하고 같은 주문의 메시지를 같은 partition에 모으는 기준으로 삼는다. |
| 적용·검증 | 주문 발행 코드 미구현. 실제 주문 이벤트의 key·payload·저장 결과 검증 전이다. |

현재 결정은 [메시지 key 설계](design.md#message-key)에 반영했다.

<a id="publish-order"></a>

## 2. 주문 commit과 메시지 전송의 순서 선택

**이 절은 발행 순서를 선택한 당시의 설계·코드 확인 기록이다. 당시에는 Java 코드 변경·테스트 실행 전이었고, 이후 선택과 구현·검증은 [3절](#after-commit-boundary)에 추가했다.**

| 구분 | 내용 |
| --- | --- |
| 검토한 질문 | 주문 확정 commit 뒤에 메시지를 보낼지, 메시지를 먼저 보내고 commit할지 비교했다. |
| 결정 | 주문 최초 확정 DB commit 성공 후 Kafka에 메시지를 전송한다. |
| 선택 이유 | 메시지를 먼저 보내면 주문 DB rollback 뒤에도 확정 메시지가 전달될 수 있다. 확정된 주문 사실만 전달하도록 commit을 먼저 확인한다. |
| 남는 문제 | commit 뒤 전송 전 중단·전송 실패로 메시지가 누락될 수 있다. 이번 주에 재현하고 다음 주 Outbox 개선의 출발점으로 삼는다. |
| 확인한 기존 코드 | TX C `OrderPaymentTransactions.applyRecordedResult()`의 SUCCESS 분기에서 `OrderModel.completePayment()`가 주문·예약을 확정한다. 해당 호출 직후는 아직 commit 전이다. |
| 연결 방식 | `AFTER_COMMIT` listener와 트랜잭션 밖 orchestration 중 선택 전. 기존 `PricePageInvalidator`에서 커밋 후 실행 패턴을 확인했다. |
| 메시지 준비 흐름 | 주문 확정 TX 안에서 필요한 값을 복사해 메시지 객체를 준비하고, DB commit 성공 후 실제 전송한다. 준비와 전송의 차이·rollback 시 미전송·주문 데이터 예시를 [설계 흐름](design.md#prepare-message)에 추가했다. 구체적인 메시지 모델은 구현 전이다. |
| 적용·검증 | 문서에 방향만 반영했다. 정상 발행·rollback 시 미발행·중복 확정·전송 실패·누락 재현은 아직 실행하지 않았다. |

결정의 범위와 실패 분기는 [설계](design.md#publish-after-commit)와 [ADR 0035](../adr/0035-publish-order-confirmation-after-db-commit.md)에 정리했다.

<a id="after-commit-boundary"></a>

## 3. AFTER_COMMIT 선택과 메시지 준비·발행 포트 호출

이 절은 발행 포트 연결 당시의 기록이다. 이후 eventId 선택·실제 Kafka 연결은 [4절](#kafka-producer)에 추가했다.

### 결정과 구현 범위

사용자가 도메인 책임 분리를 기준으로 비교한 뒤 `AFTER_COMMIT`을 선택했다. 직접 호출도 같은 책임 분리를 지킬 수 있지만, 최초 확정 지점에서 전송을 연결하고 기존 프로젝트의 커밋 후 처리 패턴과 일치시키는 쪽을 택했다.

| 책임·파일 | 구현한 내용 |
| --- | --- |
| `OrderModel` | `completePayment(Instant confirmedAt)`에서 확정 시각을 상태·예약 소비와 함께 저장. null 시각은 상태 변경 전에 거절. 두 번째 확정은 기존 상태 검증으로 거절 |
| `OrderPaymentTransactions` | 주입한 Clock의 시각을 MySQL `datetime(6)`에 맞춰 마이크로초로 절삭. 최초 SUCCESS 전이에만 불변 `OrderConfirmed` 구성·Spring 내부 이벤트 전달 |
| `OrderConfirmed` | 주문 ID·확정 시각·품목 값을 복사한 불변 스냅샷. 상품별 합산·ID순 정렬, 잘못된 값·빈 품목·합산 overflow 검증 |
| `OrderConfirmationPublisher` | domain에 기술 독립적인 발행 인터페이스 정의 |
| `OrderConfirmationListener` | application에서 `AFTER_COMMIT`에 발행 인터페이스 호출. 동기 예외는 오류 로그로 남기고 이미 확정된 주문 응답·재고·PG 생성 횟수를 유지 |

listener는 `commerce.order-events.enabled=true`일 때 등록한다. 현재는 실제 전송 구현체를 연결하지 않아 기본 비활성이며, 통합 테스트에서 발행 인터페이스 대역을 주입해 활성화했다. API의 Kafka 모듈 의존·topic 생성·최종 Kafka 메시지·실제 전송 구현체는 추가하지 않았다. eventId 생성 방식에 대한 사용자 선택은 아직 받지 않았다.

새 컬럼은 nullable이다. DRAFT·미확정·실패·기존 POINT 확정의 시각을 임의로 채우지 않으며, 이미 완료된 PG 주문의 반복 적용도 시각을 다시 만들거나 새 이벤트를 준비하지 않는다. local·test는 기존 `ddl-auto: create`를 사용했고 외부 DB에 스키마 변경이나 백필은 실행하지 않았다.

### 실행 검증과 범위

처음에는 새 필드·메서드·스냅샷이 없어 테스트 컴파일이 실패하는 것을 확인한 뒤 최소 구현했다. 주문 모델 테스트 통과 후 실제 MySQL 테스트 컨테이너·기존 가짜 PG 경로를 사용하는 `OrderConfirmationEventIntegrationTest`를 실행했다. 발행 포트만 Mockito 대역이므로 실제 broker record를 확인한 검증은 아니다.

| 검사 | 확인한 내용·현재 결과 |
| --- | --- |
| `OrderModelTest` | 최초 확정 시각 저장, 반복 확정으로 시각 변경 금지, null 시각 거절 시 상태 보존, POINT·대기·실패의 시각 미생성. 관련 실행 통과 |
| `OrderConfirmedTest` | 중복 상품 수량 합산·정렬·원본 목록 변경과의 분리·불변 목록, 잘못된 사실·수량·합산 overflow 거절. 관련 실행 통과 |
| `OrderConfirmationEventIntegrationTest` 초기 5건 | 커밋 전 발행 미호출, listener가 호출한 별도 `REQUIRES_NEW` 트랜잭션에서 확정·예약·시각 조회, 반복 적용·callback·reconcile의 추가 호출 없음, rollback 후 미발행과 시각 rollback·복구, PENDING·FAILED 미발행, 동기 발행 오류 후 확정 상태·재고·PG POST 1회 유지. 통과 |
| UNKNOWN·동시 적용 추가 검사 | 조회 불가 시 미발행, 두 실행의 동시 결과 적용에서 발행 포트 1회 호출 검증 통과. 최종 `OrderConfirmationEventIntegrationTest`는 7건 통과 |
| `:apps:commerce-api:check` | **BUILD SUCCESSFUL** (4분 30초). 기본 check 대상 71개 suite·515건, 실패·오류·skip 0. 기존 설정대로 experiment 태그는 실행 대상에서 제외 |
| Checkstyle·ArchUnit | main/test Checkstyle 위반 0. 전체 검사에 포함된 `LayeredArchitectureTest` 1건 통과 |
| 실제 Kafka·streamer·offset·누락 실험 | 미실행·미구현 |

Docker 29.7.2에서 기본 Testcontainers 연결은 HTTP 400으로 실패했다. 기존 프로젝트에서 사용한 `JAVA_TOOL_OPTIONS=-Dapi.version=1.44`를 적용한 뒤 MySQL 컨테이너 연결과 위 관련 검사가 통과했다. Java 21·격리된 테스트 DB를 사용했다.

```shell
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home \
PATH=/opt/homebrew/opt/openjdk@21/bin:$PATH \
JAVA_TOOL_OPTIONS=-Dapi.version=1.44 \
./gradlew :apps:commerce-api:test \
  --tests '*OrderModelTest' \
  --tests '*OrderConfirmedTest' \
  --tests '*OrderConfirmationEventIntegrationTest' --offline

# UNKNOWN·동시 적용 검사를 추가한 후 실행한 전체 검사
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home \
PATH=/opt/homebrew/opt/openjdk@21/bin:$PATH \
JAVA_TOOL_OPTIONS=-Dapi.version=1.44 \
./gradlew :apps:commerce-api:check --offline
```

### 문서·책임 대조

`OrderModel`에 Kafka·Spring 이벤트 발행을 넣지 않았고 application은 domain의 발행 인터페이스에 의존한다. 현재 주차 설계·이 기록·ADR 0035에 구현 범위를 반영했다. 처음에는 기존 ADR 0029·0030의 메서드 인자와 ADR 0032의 TX C 저장 범위를 본문에서 바꿨으나, 사용자의 ADR 이력 보존 확인 뒤 해당 본문은 원래대로 복원했다. 이전 ADR 상단에는 후속 확장 링크만 추가하고 6주차의 새 결정은 ADR 0035에 둔다. 이전 주차 설계·구현 기록은 수정하지 않았다. 변경한 상태 전이·잠금·중복·rollback·동기 발행 오류 분기를 코드와 대조했고, 현재 구현한 범위에서 추가 수정이 필요한 계약 위반은 발견하지 않았다. 실제 Kafka 전송·consumer·offset은 이 실행 당시 미구현이었다.

<a id="kafka-producer"></a>

## 4. 고정 eventId 선택과 실제 Kafka 전송 연결

### 선택·구현

사용자는 eventId의 prefix가 Kafka 필수 형식인지 확인한 뒤 `order-confirmed:v1:{orderId}`를 선택했다. 같은 주문의 단 한 번인 확정 사실에서 고정 생성하며 새 UUID·시각·시도 횟수를 섞지 않는다. 사건 종류를 구별하는 이유와 대안은 새 [ADR 0036](../adr/0036-derive-order-confirmation-event-id-from-order-id.md)에 남겼다.

| 파일·책임 | 반영 내용 |
| --- | --- |
| `OrderConfirmed.eventId()` | 내부 주문 ID에서 고정 사건 ID 유도 |
| `OrderConfirmedMessage` | `OrderConfirmed`·schemaVersion 1·고정 eventId·orderId·저장된 confirmedAt·정규화한 items를 JSON 경계로 매핑. 시각은 UTC ISO-8601 문자열 |
| `KafkaOrderConfirmedPublisher` | domain 포트 구현. KafkaTemplate에 orderId 문자열 key와 JSON 모델 전달. future 성공에서 실제 metadata 기록, 비동기 실패에서 eventId·orderId·topic·원인 로그 |
| `OrderConfirmationListener` | 동기 send 오류 로그에도 eventId 포함. 이미 커밋한 주문 응답을 전송 오류로 되돌리지 않음 |
| API Gradle·application.yml | Kafka 모듈·테스트 fixture 의존과 kafka.yml import 추가. `commerce.order-events.topic=order-events`, enabled=false 기본값 명시 |
| `KafkaTestBroker` | 로컬 Compose와 같은 bitnamilegacy/kafka:3.5.1·KRaft 격리 fixture. 테스트가 명시적으로 생성할 때만 시작하고 종료 시 정리 |
| `KafkaConfig` | 설정값은 변경하지 않고 wildcard import만 명시적 import로 정리 |

전송 완료를 기다리는 `get/join`이나 `@Async`는 추가하지 않았다. KafkaTemplate의 send 호출과 future 결과 관찰을 구분한다. send 호출 중 metadata 확보 등 클라이언트 내부 대기는 남으며 API 응답이 broker 저장 완료 증거는 아니다. producer의 기존 설정·기본값은 이번 사용자 튜닝 결정으로 취급하지 않는다. 실제 broker 중단·timeout에 따른 운영 시간 예산은 후속 확인 대상이다.

업무용 `order-events`의 partition·복제 수는 아직 선택·생성하지 않았다. 로컬 기본 발행은 비활성이며 격리된 테스트에서만 명시적으로 켰다. 기존 실습용 topic·로컬 DB에 주문 데이터를 넣거나 외부 스키마를 바꾸지 않았다.

### 실제 검증

미구현 eventId·Kafka 타입·발행 클래스를 참조하는 테스트 컴파일 실패를 확인한 뒤 최소 구현했다. `OrderConfirmedTest`의 고정 ID 검사와 `KafkaOrderConfirmedPublisherTest` 4건이 통과했다. 이후 실제 PG 결과 적용→MySQL commit→Kafka 3.5.1을 연결한 검사를 실행했다.

| 검사 | 실제 확인 범위·결과 |
| --- | --- |
| `OrderConfirmedTest` 추가 1건 | 주문 42의 반복 생성은 `order-confirmed:v1:42`, 주문 43은 다른 고정 ID. 기존 3건과 함께 통과 |
| `KafkaOrderConfirmedPublisherTest` 4건 | 정확한 6개 JSON 필드·key·저장 시각, future 완료 전 성공 로그 없음, 성공 metadata, 비동기 future 실패 로그, 동기 send 오류의 listener 관찰. 통과. 브로커 장애를 실제로 주입한 테스트는 아님 |
| `OrderConfirmedPublicationTest` 2건 | 실제 주문 최초 확정의 1건 발행, key·JSON·DB 시각 일치, 반복 callback·reconcile·결과 적용의 추가 record 없음, 같은 사실의 명시적 재전송에서 같은 ID·partition과 offset 증가, TX C rollback의 record 0건과 복구 후 1건. 통과 |
| `OrderConfirmationEventIntegrationTest` 7건 | Kafka 모듈을 연결한 뒤에도 기존 포트 경계 검사 통과 |
| `:apps:commerce-api:check :modules:kafka:check` | **BUILD SUCCESSFUL** (4분 42초). API 73개 suite·522건, 실패·오류·skip 0. ArchUnit 포함. API main/test·Kafka main/testFixtures Checkstyle 위반 0. Kafka 모듈 자체 단위 테스트는 NO-SOURCE이며 실제 broker 검사는 API에서 실행 |

실제 Kafka 검사 조건은 고유 suffix topic·partition 2·복제 수 1이다. 테스트 reader는 수동 partition 배정과 String 역직렬화로 record를 점검한다. 업무용 consumer group 배정·ack·재전달·구매 활동 DB의 검증이 아니다. 각 테스트는 격리 topic·DB 상태를 초기화하고 producer metadata도 재설정한다. 완료 시 전용 broker·PG·DB 자원을 정리했다.

관련 검사 실행에서 관찰한 broker metadata 예시는 다음과 같다. 숫자는 합성 예시가 아니라 격리 실행에서 생성된 실제 값이다. 테스트 간 DB·topic이 초기화되므로 서로 다른 테스트의 `orderId=1`을 같은 주문의 연속 이력으로 보지 않는다.

```text
topic: order-events-publication-d1a07f08-0e8c-4b34-959e-2624e3ab57ff
eventId: order-confirmed:v1:1
최초 확정: partition=1, offset=0
같은 확정 사실의 명시적 재전송: partition=1, offset=1
```

이것은 producer의 재전송을 확인한 결과다. Kafka에는 같은 업무 사실의 record가 두 건 생길 수 있으므로, 구매 활동을 한 번만 저장하는 보장은 후속 consumer의 DB 중복 보호로 검증해야 한다.

```shell
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home \
PATH=/opt/homebrew/opt/openjdk@21/bin:$PATH \
JAVA_TOOL_OPTIONS=-Dapi.version=1.44 \
./gradlew :apps:commerce-api:test \
  --tests '*OrderConfirmedPublicationTest' \
  --tests '*OrderConfirmationEventIntegrationTest' --offline

JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home \
PATH=/opt/homebrew/opt/openjdk@21/bin:$PATH \
JAVA_TOOL_OPTIONS=-Dapi.version=1.44 \
./gradlew :apps:commerce-api:check :modules:kafka:check --offline
```

### ADR 이력·작업 규칙 정리

사용자의 요청으로 AGENTS.md에 채택 ADR 본문 보존, 새 결정의 새 ADR 작성, 이전 ADR의 대체·확장 메타데이터와 후속 링크, design·implementation-log의 현재 상태 관리 책임을 명시했다. 0029·0030·0032는 기존 본문을 원래대로 두고 상단의 확장 링크만 추가했다. 0035의 검증 건수·당시 구현 상태는 보존하고 후속 결정 0036과 현재 기록으로 연결했다. 현재 구현·검증 건수를 맞추기 위해 이전 ADR 본문을 다시 쓰지 않는다.

현재 구현 범위의 코드·계약·문서·테스트를 대조했다. application은 domain 발행 포트에 의존하고 Kafka 구현은 infrastructure에 있다. 트랜잭션·잠금 경계와 중복·rollback·동기/비동기 오류 처리를 확인했으며, 이 범위에서 추가 수정이 필요한 계약 위반은 발견하지 않았다. broker 중단·전송 전 누락·구매 활동·offset 재전달은 후속 검증으로 남는다.

<a id="three-brokers"></a>

## 5. 브로커 3개 실험 기반만 준비

사용자가 여러 브로커에서 설정 변화의 차이를 실험하기로 하고 **브로커 3개만 먼저 세팅하며 나머지는 미리 정하지 말 것**을 요청했다.

`docker/kafka-cluster-compose.yml`을 추가하고 Docker Desktop의 `desktop-linux`에서 `week6-kafka` 프로젝트를 시작했다. Kafka 3.5.1의 broker·controller 겸용 서버 3개와 Kafka UI만 실행했다. 공통 KRaft cluster ID·서로 다른 node ID·서버 간 주소·호스트 포트·각 서버의 새 volume·UI 연결을 구성했다.

기존 `docker/infra-compose.yml`과 단일 브로커의 volume은 변경하지 않았다. 기존 `kafka`·`kafka-ui`는 실행 전부터 중지 상태였으며 그대로 보존했다. 업무용 토픽 생성, 메시지 전송, producer·consumer 실행, partition·topic 복제 수·acks·최소 ISR·튜닝 설정 변경은 하지 않았다. 실행에 필수적인 서버·연결 설정 외에 새 선택을 추가하지 않았다.

| 확인 | 실제 결과 |
| --- | --- |
| Compose config | `config --quiet` 성공 |
| 서버 상태 | `week6-kafka-1`, `week6-kafka-2`, `week6-kafka-3` 모두 healthy |
| broker API metadata | node ID 1·2·3, 내부 주소 `kafka-1:9092`·`kafka-2:9092`·`kafka-3:9092` 확인 |
| KRaft metadata | cluster ID `qZYvnMj7TQWEzWrswnJt1A`, CurrentVoters `[1,2,3]`, 조회 시 LeaderId 3·MaxFollowerLag 0 |
| topic CLI | 목록 비어 있음 |
| UI `/api/clusters` | `name=week6`, `status=online`, `brokerCount=3`, `topicCount=0`, `onlinePartitionCount=0`, `lastError=null` |

KRaft의 LeaderId는 조회 당시 관리 quorum의 리더다. 아직 생성하지 않은 사용자 topic partition의 리더를 뜻하지 않는다. 이번에는 장애·복제·전송 실험을 하지 않았다.

```shell
docker --context desktop-linux compose -f docker/kafka-cluster-compose.yml config --quiet
docker --context desktop-linux compose -f docker/kafka-cluster-compose.yml up -d
docker --context desktop-linux compose -f docker/kafka-cluster-compose.yml ps
docker --context desktop-linux compose -f docker/kafka-cluster-compose.yml exec -T kafka-1 kafka-metadata-quorum.sh --bootstrap-server localhost:9092 describe --status
docker --context desktop-linux compose -f docker/kafka-cluster-compose.yml exec -T kafka-1 kafka-broker-api-versions.sh --bootstrap-server localhost:9092
docker --context desktop-linux compose -f docker/kafka-cluster-compose.yml exec -T kafka-1 kafka-topics.sh --bootstrap-server localhost:9092 --list
curl --fail --silent --show-error --max-time 10 http://localhost:9099/api/clusters
```

브로커 3개만 결정한 범위를 design·ADR 0037과 대조했다. 이번 변경은 Compose와 환경 문서이며 Java 코드·애플리케이션 설정은 변경하지 않았다. 위 환경 검증을 실행했고, 이전 절의 Gradle 522건을 이번 변경에서 재실행한 결과로 표현하지 않는다.

<a id="client-config-correction"></a>

## 6. 설정 키·접속 주소 정정과 실효 기본값 설명

사용자가 리뷰 내용을 확인한 뒤 **consumer 설정 오타, 브로커 3개에 맞는 접속 주소, 문서의 기본값 설명만 먼저 수정**하도록 요청했다. 토픽 생성 정책·복제 수·offset 시작 위치·acks·timeout은 새로 선택하거나 변경하지 않았다.

### 변경 범위

| 대상 | 정정 |
| --- | --- |
| kafka.yml consumer | `value-serializer` → `value-deserializer`. 원래 적혀 있던 `ByteArrayDeserializer` 적용 |
| kafka.yml auto commit | 임의 properties의 잘못된 `enable-auto-commit` 키를 제거하고 Boot의 `consumer.enable-auto-commit=false` 사용. 실제 client map에 `enable.auto.commit=false`가 전달됨 |
| kafka.yml local/test | 공통 bootstrap을 `localhost:19092`·`localhost:29092`·`localhost:39092`로 설정 |
| kafka.yml admin | 예전 `kafka:9092` override 제거. producer·consumer·admin이 공통 bootstrap을 사용하고 테스트의 공통 주소 override도 함께 따름 |
| 문서·주석 | broker 기본값은 실제 동작 중인 값임을 명시. 클라이언트의 `auto.create.topics.enable=false`가 broker 자동 생성을 끄지 못한다는 주석 추가. 기존·새 Compose의 동시 실행 불가 설명 명시 |

직전 리뷰에서는 broker 1·2·3 모두 `auto.create.topics.enable=true`, `num.partitions=1`, `default.replication.factor=1`, `min.insync.replicas=1`, `offsets.topic.replication.factor=1`을 확인했다. 이번 수정 중에도 broker 1을 읽기 전용으로 재조회해 동일 값을 확인했고, Compose나 실행 중인 broker 설정은 변경하지 않았다. 이 값들을 사용자 선택으로 간주하지 않도록 design.md에 출처·의미·남은 선택을 구분했다.

### 실행 검증

`KafkaClientConfigurationTest`는 실제 kafka.yml을 ConfigData로 읽고 Spring의 KafkaProperties 바인딩 결과를 검사한다. broker나 consumer를 시작하지 않는다. 수정 전 local/test의 역직렬화·auto commit, 세 client의 주소, admin의 override 상속을 검증하는 5건 모두 실패했고, 최소 설정 수정 뒤 5건 모두 통과했다.

| 검사 | 결과 |
| --- | --- |
| `:modules:kafka:check` | 설정 바인딩 5건 통과, 실패·오류·skip 0. main/test/testFixtures Checkstyle 위반 0 |
| 관련 API 테스트 | `OrderConfirmedPublicationTest` 2건 + `OrderConfirmationEventIntegrationTest` 7건 + `LayeredArchitectureTest` 1건, 총 10건 통과. 실제 격리 Kafka 전송·rollback 검증 포함 |
| API Checkstyle | main/test 검사 UP-TO-DATE, 기존 보고서 위반 0. 이번 수정으로 API Java 소스는 변경하지 않음 |
| `:apps:commerce-streamer:check` | 컴파일·main Checkstyle 성공. streamer 테스트는 아직 NO-SOURCE이며 실제 구매 활동 consumer 검증은 아님 |
| 합친 명령 | **BUILD SUCCESSFUL**, 49초 |
| 실행 중인 로컬 클러스터 | broker 3개 모두 healthy·topic 목록 비어 있음 확인. broker 재시작·설정 변경 없음 |

```shell
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home \
PATH=/opt/homebrew/opt/openjdk@21/bin:$PATH \
JAVA_TOOL_OPTIONS=-Dapi.version=1.44 \
./gradlew :modules:kafka:check \
  :apps:commerce-api:test \
  --tests '*ArchitectureTest' \
  --tests '*OrderConfirmedPublicationTest' \
  --tests '*OrderConfirmationEventIntegrationTest' \
  :apps:commerce-api:checkstyleMain :apps:commerce-api:checkstyleTest \
  :apps:commerce-streamer:check --offline
```

이는 이번 설정 정정에 대한 관련 검사 결과다. 이전 절의 전체 API 522건을 재실행한 결과로 표현하지 않는다. 실제 전송 회귀는 기존 테스트의 전용 broker·DB에서 실행했으며, 사용자의 실행 중인 브로커 3개 클러스터에는 토픽 생성·메시지 주입·장애 실험을 하지 않았다.

설정 키·주소·바인딩과 현재 주차 문서를 대조했다. 이번에는 기존 설정의 오타·연결 정정이며 새 실험 정책을 채택하지 않았으므로 새 ADR을 추가하거나 채택된 ADR 본문을 수정하지 않았다.

<a id="publication-gap-verification"></a>

## 7. commit 후 send 직전 중단의 발행 누락 검증

`OrderConfirmedPublicationTest.confirmationCommittedButStoppedBeforeSendLeavesNoRecord`를 Docker Desktop 연결 후 실행했다. 운영 발행 코드·로컬 3대 클러스터 설정은 변경하지 않았다.

테스트 전용 `@Primary` publisher hook이 특정 주문 A만 실제 send 직전에 기록하고 정상 반환한다. 주문 B는 실제 Kafka publisher로 전달한다. 두 주문은 실제 PG fixture의 SUCCESS 결과를 기존 reconcile 경로로 적용한다. 검사 결과 A는 CONFIRMED·예약 CONSUMED이며 hook이 A의 eventId를 받았고, 전체 topic record는 B의 key를 가진 1건뿐이었다. 기존 정상 발행·동일 사실 재전송과 rollback 후 복구 검사도 함께 통과했다.

| 검사 | 결과 |
| --- | --- |
| `OrderConfirmedPublicationTest` | 기존 2건 + 새 누락 재현 1건, 총 3건 통과. 실패·오류·skip 0 |
| `LayeredArchitectureTest` | 1건 통과. 실패·오류·skip 0 |
| API Checkstyle main/test | UP-TO-DATE, 보고서 위반 0 |
| 합친 명령 | **BUILD SUCCESSFUL**, 44초 |

```shell
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home \
PATH=/opt/homebrew/opt/openjdk@21/bin:$PATH \
JAVA_TOOL_OPTIONS=-Dapi.version=1.44 \
./gradlew :apps:commerce-api:test \
  --tests '*OrderConfirmedPublicationTest' --tests '*ArchitectureTest' \
  :apps:commerce-api:checkstyleMain :apps:commerce-api:checkstyleTest --offline
```

실제 MySQL·Kafka는 테스트 전용 컨테이너다. 이번 hook은 OS 강제 종료가 아니라 send 호출을 건너뛰는 통제된 중단이며, 구매 활동 DB·consumer group·offset commit·재전달·다중 broker 장애를 검증하지 않는다. consumer 구현 뒤 같은 사례에 A의 활동 없음·B의 활동 저장을 연결하고, W7에 넘길 재현 정보·실제 사건 사실을 추가해야 한다. 과거 전체 검사 건수를 이번 실행 결과로 표현하지 않는다.

이 검사는 ADR 0035의 DB commit 후 직접 발행에 남는 실패 구간을 확인한 것이며, 채택된 ADR 본문은 변경하지 않았다. design.md에는 실행한 범위·남은 범위를 맞춰 반영했다.

<a id="purchase-activity-storage"></a>

## 8. 상품별 구매 활동 저장 기초와 주문 단위 DB 트랜잭션

**이 절은 저장 기초를 구현한 당시의 기록이다. 완료 이벤트 기록·동일 사실 no-op과 최신 검증은 [9절](#idempotent-purchase-handling)에 추가했다.**

사용자가 `(orderId, productId)`를 구매 활동의 중복 기준으로 선택하고, 한 주문의 중간 품목 실패 시 모든 저장을 취소하도록 요청했다. 주문 메시지 한 건의 items에 여러 상품을 담는 기존 계약은 유지한다. 이번에는 streamer의 DB 저장 기초만 구현했으며 실제 Kafka listener·처리 완료 이벤트 기록·중복 no-op까지 구현한 것으로 표현하지 않는다.

### 책임과 저장 경계

- domain `OrderPurchase`: orderId·confirmedAt·품목의 불변 입력. 양수 ID·수량·누락 값 검증, 같은 상품 수량 합산·ID순 정렬, 합산 overflow 거절.
- domain `ProductPurchaseActivity`: `product_purchase_activity`의 상품별 구매 기록. orderId·productId·quantity·confirmedAt을 보존하고 `(order_id, product_id)` 유일 제약을 적용한다. 현재 상품·주문 조회나 FK는 없다.
- application `PurchaseActivityRecorder.record`: 모든 품목을 같은 DB 트랜잭션에서 저장한다. domain 저장 포트를 사용하고 예외를 전파해 전체 rollback한다.
- infrastructure `PurchaseActivityRepositoryImpl`·JPA repository: 실제 저장. 정상 중복 no-op이나 payload 대조 로직은 아직 없다. 동일 조합의 insert는 DB 제약 위반으로 반환된다.

### 실행 검증

먼저 두 품목을 개별 저장하는 상태에서 오류 테스트를 실행했다. 두 번째 저장이 실패해도 첫 번째 행이 남아 런타임 오류·DB 중복 제약 오류의 rollback 테스트 2건이 기대대로 실패했다. 주문별 저장 메서드에 `@Transactional`을 적용한 뒤 전체 streamer 검사를 실행했다.

첫 테스트 구성에서 기존 수동 listener factory가 Boot의 `spring.kafka.listener.auto-startup=false`를 따르지 않아 demo listener도 시작되는 것을 발견했다. 최종 DB 테스트는 JPA 구성·실제 MySQL fixture·대상 저장 객체만 로드하고 Kafka 자동 구성을 제외한다. 최종 실행 결과에는 consumer 시작·bootstrap 접속 로그가 없으며 실제 Kafka 소비를 검증한 것이 아니다. 운영 factory·demo·브로커 설정은 변경하지 않았다.

| 검사 | 결과 |
| --- | --- |
| `OrderPurchaseTest` | 정규화·불변성·잘못된 사실·수량 overflow·잘못된 활동 거절 4건 통과 |
| `PurchaseActivityRecorderTest` | 실제 MySQL 6건 통과: 상품별 수량·확정 시각 저장, 다른 주문의 같은 상품, 중복 조합 거절, 일반 오류·DB 오류 전체 rollback, 동시 중복 경쟁 |
| streamer ArchUnit | 기존 API와 같은 계층 접근 규칙 1건 통과 |
| streamer Checkstyle | main/test 모두 실행, 위반 0 |
| API ArchUnit | 1건 통과. 전체 API 기능 검사는 이번에 재실행하지 않음 |
| 합친 명령 | **BUILD SUCCESSFUL**, 26초. 총 12건, 실패·오류·skip 0 |

```shell
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home \
PATH=/opt/homebrew/opt/openjdk@21/bin:$PATH \
JAVA_TOOL_OPTIONS=-Dapi.version=1.44 \
./gradlew :apps:commerce-streamer:check \
  :apps:commerce-api:test --tests '*ArchitectureTest' --offline
```

동시성 검사에서는 두 호출 중 하나가 저장에 성공하고 다른 하나가 DB 유일 제약 위반으로 실패하며 최종 구매 활동은 2행이다. 이는 DB 중복 보호의 증거이며 같은 이벤트 재전달을 정상 no-op으로 처리한 결과는 아니다. 중간 DB 오류 검사는 기존에 commit된 상품 8 기록은 유지하고 이번 호출에서 먼저 삽입한 상품 7만 rollback하는 것도 확인했다.

주문별 트랜잭션·상품별 유일성을 ADR 0038에 기록했다. ADR 0035는 Accepted와 기존 본문을 유지하고 확장 관계만 상단에 추가했다. 현재 모델·책임·정상·실패·중복·동시 경쟁 범위를 design.md와 대조했다. 토픽·브로커·consumer group·ack·offset 시작 위치와 전송 활성화는 변경하지 않았다.

<a id="idempotent-purchase-handling"></a>

## 9. 완료 이벤트 기록과 구매 활동의 원자적 저장·동일 사실 no-op

**이 절의 24건은 DB handler를 완성한 당시의 결과다. 이후 수신 코드 준비와 최신 검증은 [10절](#consumer-preparation)에 추가했다.**

사용자가 완료 이벤트 기록을 상품별 활동과 같은 MySQL 트랜잭션으로 묶고, 동일한 이벤트가 다시 도착하면 추가 저장 없이 정상 종료하도록 구현하는 데 동의했다. 완료 이벤트 기록과 Kafka offset은 별개이며, 이번 단계는 DB handler까지만 구현한다.

### 구현과 분기

- `OrderPurchase`에 eventId를 추가했다. 정규화된 `orderId|Instant|productId:quantity,...` 비교 snapshot을 생성한다. eventId는 비어 있지 않고 255자 이내이며, 기존 주문·시각·양수 수량 검증과 품목 합산·정렬은 유지했다. Kafka JSON·producer는 변경하지 않았다.
- `processed_order_confirmation`은 eventId·orderId에 각각 유일 제약을 두고 고정 사실 snapshot을 longtext에 저장한다. domain 모델이 동일 사실을 대조하며, ID·주문·시각·수량·품목이 다르면 충돌 예외를 전파한다.
- `PurchaseActivityRecorder.record()`의 새 TX에서 완료 기록과 모든 활동을 저장한다. 기존 완료 사실을 찾으면 대조 후 no-op한다. 완료 기록은 insert 시점에 별도 commit되지 않으며 품목과 함께 commit·rollback된다.
- `OrderConfirmationHandler`가 recorder의 트랜잭션 종료 이후 경쟁을 조정한다. 완료 테이블의 알려진 eventId/orderId 제약과 MySQL 중복 코드 1062에 해당하는 경우만 infrastructure에서 경쟁 예외로 변환한다.
- 경쟁에 진 호출은 쓰기 TX rollback 뒤 `verifyRecorded()`의 새 읽기 TX에서 승자의 사실을 확인한다. 같은 사실이면 정상, 다른 사실이면 충돌, 기록이 없으면 원래 경쟁 예외를 전파한다. 일반 무결성 오류·활동 테이블 오류·런타임 실패는 중복으로 숨기지 않는다.

### 테스트 변경과 실행

먼저 완료 기록을 매번 insert하는 상태에서 동일 이벤트의 정규화된 재전달 테스트를 실행해 중복 제약 예외로 실패하는 것을 확인했다. 이후 기존 사실 대조·경쟁 복구를 연결했다. 기존 활동 유일성 검사는 저장 포트의 실제 중복 insert를 계속 검증하며, 중간 DB 오류 검사는 완료 기록 없이 기존 활동만 있는 상태를 준비해 새 완료 기록과 첫 활동이 함께 rollback되는지 확인한다. 동시성 기대값은 기존의 성공 1회·제약 실패 1회에서 **동일 사실이면 두 호출 모두 정상 종료**로 확장했다. 활동 행 수·수량 불변 검사는 유지했다.

| 검사 | 최종 결과 |
| --- | --- |
| `PurchaseActivityRecorderTest` | 실제 MySQL 15건 통과. 정상 저장·정규화 no-op·활동 유일성·다른 주문·오류 rollback 후 재시도·충돌 6가지·동일/다른 ID 동시 경쟁·승자 부재 오류 |
| `OrderPurchaseTest` | 4건 통과. eventId 포함 유효성·정규화·불변성·overflow·활동 검증 |
| `ProcessedOrderConfirmationRepositoryTest` | 4건 통과. 알려진 식별자 중복 2가지의 변환과 다른 제약·일반 무결성 오류의 전파 |
| streamer ArchUnit | 1건 통과. application이 domain 포트에 의존하고 infrastructure 구현을 직접 참조하지 않음 |
| streamer Checkstyle | main/test 모두 실행, 위반 0 |
| 최종 `:apps:commerce-streamer:check` | **BUILD SUCCESSFUL**, 27초. 총 24건, 실패·오류·skip 0 |
| API ArchUnit | 직전 합친 검사에서 UP-TO-DATE. API Java는 변경하지 않았고 전체 API 기능 검사는 이번에 재실행하지 않음 |

동시성 테스트는 두 호출이 모두 완료 기록을 못 찾은 뒤 insert 직전에 합류하도록 통제해 실제 DB 유일 제약 경쟁을 일으킨다. 동일 이벤트는 두 호출 모두 정상 완료하고 활동 2행·완료 기록 1행이 남았다. 실패 후 확인은 readOnly인 실제 활성 TX에서 실행됐고, 두 쓰기 TX와 다른 EntityManager resource를 사용하는 것도 확인했다. 같은 주문의 다른 eventId 경쟁은 성공 1회·충돌 1회로 드러났다.

```shell
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home \
PATH=/opt/homebrew/opt/openjdk@21/bin:$PATH \
JAVA_TOOL_OPTIONS=-Dapi.version=1.44 \
./gradlew :apps:commerce-streamer:check --offline
```

실제 DB는 테스트 전용 MySQL이며 Kafka 자동 구성·listener는 테스트에 로드하지 않는다. 이 실행은 실제 Kafka poll·ack·committed offset·재전달·broker 장애 검증이 아니다. 토픽·브로커 설정·consumer group·ack·offset 시작 위치·전송 활성화는 변경하지 않았고, 실행 중인 업무용 DB나 로컬 Kafka 클러스터에 데이터를 주입하지 않았다.

현재 구조·입력·정규화·정상·중복·충돌·rollback·경쟁 확인 경계와 실제 테스트 범위를 design.md와 대조했다. 새 결정은 ADR 0039에 기록했고 ADR 0038의 본문은 보존한 채 상단에 확장 관계만 추가했다.

<a id="consumer-preparation"></a>

## 10. 소비 설정을 미루고 자동 구독 없는 기본 수신 코드 준비

사용자는 처음 읽을 위치 등 소비 설정을 기본 준비 이후에 정할 수 있는지 물었다. parser·수신 어댑터와 DB 연결을 먼저 준비하고 실제 구독·offset 변경은 선택 이후에 활성화하는 범위로 진행했다.

### 구현과 경계

- `OrderConfirmedMessageParser`가 String key·JSON bytes를 검증한다. eventType=OrderConfirmed·정수 schemaVersion=1과 필수 값·양의 정수 범위를 확인한다. key와 orderId 문자열은 같아야 하며 수량을 소수·문자열에서 정수로 강제 변환하지 않는다.
- confirmedAt은 Instant로 읽고 source 주문의 datetime(6)과 같은 마이크로초 정밀도를 요구한다. 추가 정밀도를 조용히 줄이지 않는다. 잘못된 JSON·빈 본문·여러 JSON 문서·잘못된 시각·합산 overflow는 실패한다. 추가 JSON 필드는 업무 사실에 사용하지 않으며 다른 버전·이벤트 종류는 수용하지 않는다.
- `OrderConfirmationCommand`가 application 입력을 맡는다. 내부의 domain 입력으로 기존 검증·정규화를 재사용하고 수신 계층에 domain 객체를 반환하지 않는다. handler의 command 진입점은 기존 DB 경로로 연결된다.
- `OrderConfirmedRecordAdapter`가 parser와 handler를 호출하고 오류를 전파한다. `@KafkaListener`·업무용 factory·group·ack·offset commit은 아직 등록하지 않았다.
- demo는 `demo-kafka.enabled=true`일 때만 등록하고 기본 false로 두었다. 공통 YAML import는 classpath로 명시했다. 공유 KafkaConfig·broker/topic·producer·소비 시작 위치는 변경하지 않았다.

### 실행 검증

새 parser·adapter를 참조하는 테스트의 컴파일 실패를 먼저 확인했다. 첫 전체 검사는 기존 계층 규칙의 직접 interfaces→domain 의존과 설정 로더에서 bare YAML import가 file 경로로 해석되는 문제를 드러냈다. application command로 의존 방향을 정리하고 실제 설정의 import에 classpath를 명시한 뒤 같은 검사를 다시 실행했다. 검사 규칙·기대 결과는 유지했다.

| 검사 | 최종 결과 |
| --- | --- |
| `OrderConfirmedMessageParserTest` | 정상 계약·정규화 1건 + 잘못된 key/JSON/타입/버전/시각/품목 등 27건, 총 28건 통과 |
| `OrderConfirmedRecordAdapterTest` | 검증된 입력 전달·잘못된 입력의 handler 미호출·DB 오류 전파 3건 통과 |
| `ConsumerPreparationConfigurationTest` | 실제 application.yml·기존 KafkaConfig를 로드한 local/test 2건 통과. demo 없음·record 어댑터 있음·listener registry 구독 0개 |
| `PurchaseActivityRecorderTest` | 16건 통과. 새 합성 ConsumerRecord→parser→application→실제 MySQL 저장·반복 no-op 1건 포함 |
| 기존 domain·오류 변환·ArchUnit | 4건·4건·1건 모두 통과 |
| Checkstyle | main/test 모두 실행, 위반 0 |
| `:apps:commerce-streamer:check` | **BUILD SUCCESSFUL**, 25초. 총 58건, 실패·오류·skip 0 |

```shell
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home \
PATH=/opt/homebrew/opt/openjdk@21/bin:$PATH \
JAVA_TOOL_OPTIONS=-Dapi.version=1.44 \
./gradlew :apps:commerce-streamer:check --offline
```

합성 record는 테스트가 직접 만든 객체이고 실제 Kafka에서 poll한 record가 아니다. MySQL은 테스트 전용 fixture이며 저장 결과는 commit 이후 조회했다. 실제 Kafka listener·ack/error handler·committed offset·재전달·broker 장애와 전체 API 기능 검사는 이번에 실행하지 않았다. 자동 구독 0개 검사는 수신 준비 구성과 실제 YAML의 local/test 조건을 확인한 것이며 전체 운영 배포 검증은 아니다.

수신 계층→application 입력/handler→domain·저장 포트의 방향, 정상·parse 실패·DB 실패·반복 호출과 아직 없는 Kafka 연결을 코드·design.md와 대조했다. ADR 0040을 추가하고 ADR 0039 본문은 보존한 채 상단에 확장 관계만 표시했다. 이전 주차 문서는 변경하지 않았다.

<a id="initial-offset-experiment"></a>

## 11. earliest/latest 실제 Kafka 비교와 선택 대기

사용자는 두 설정의 차이를 실제로 확인하고 ADR에도 결과를 남기도록 요청했다. `KafkaOffsetResetExperimentTest`를 추가하고 업무용 설정은 변경하지 않았다. Testcontainers의 Kafka 3.5.1 broker 1개와 고유 topic·group을 사용하며 topic의 partition 1·복제 수 1은 offset 관찰만을 위한 격리 조건이다.

| 실제 관찰 | 결과 |
| --- | --- |
| 새 그룹, 기존 offset 0·1·2의 메시지 3건 | earliest는 3건, latest는 0건 |
| 두 그룹 초기화 후 추가한 offset 3·4의 메시지 2건 | 둘 다 2건 |
| 비교 종료 후 Kafka의 end offset | 5. 메시지 5건이 남아 있음 |
| 별도 topic·같은 group에 committed offset 3 저장 후 새 consumer로 재시작 | earliest/latest 모두 offset 3·4의 2건을 읽음 |

첫 실험은 두 group의 committed offset이 없는 것을 실제 조회했다. latest가 partition을 배정받고 position 3으로 초기화된 뒤 새 메시지를 발행해 시작 시점의 혼동을 막았다. 두 번째 실험은 비교 조건을 만들기 위해 테스트가 offset 3을 명시적으로 commit했으며, 이후 reader들은 commit하지 않아 두 설정이 같은 조건에서 실행됐다. 실제 업무 처리나 DB 성공 뒤의 미커밋 재전달 실험으로 표현하지 않는다.

새 실험 2건·ArchUnit 1건이 통과했고 실패·오류·skip은 0건이다. test Checkstyle 위반은 0건이며 합친 명령은 **BUILD SUCCESSFUL**, 32초였다. 직전 절의 전체 58건을 이번에 다시 실행한 것으로 표현하지 않는다. main 코드는 변경하지 않았으며 업무용 producer/consumer·로컬 3대 클러스터에는 데이터를 주입하지 않았다. 실험 client·broker는 종료했다.

```shell
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home \
PATH=/opt/homebrew/opt/openjdk@21/bin:$PATH \
JAVA_TOOL_OPTIONS=-Dapi.version=1.44 \
./gradlew :apps:commerce-streamer:test --tests '*KafkaOffsetResetExperimentTest' \
  :apps:commerce-streamer:checkstyleTest --offline
```

당시 결과는 선택 대기인 ADR 초안으로 정리했다. 이후 실험과 채택한 결정을 분리하기로 하여 [Kafka 설정 비교 실험](kafka-settings-experiments.md#initial-offset-experiment)으로 옮겼다. 기존 ADR 0040은 Accepted 본문을 보존하고 상단의 실험 문서 링크만 갱신했다. 현재 소비 값·기본 비활성은 유지하며 실험의 결과와 아직 없는 실제 업무 listener·offset/DB 연계 범위를 design.md와 대조했다.

<a id="consumer-count-experiment"></a>

## 12. 같은 그룹의 consumer 수 한 항목만 비교

사용자는 이후 선택도 비교 후 선택 대기 상태로 남기되 한 번에 한 항목만 진행하도록 요청했다. 먼저 첫 읽기 위치 실험의 가로 3열 흐름도를 세로 3단계로 고쳐 줄맞춤을 정리했다. 다음 비교는 같은 group의 consumer 수로 한정했다.

`KafkaConsumerCountExperimentTest`는 실제 Kafka 3.5.1의 격리 topic(partition 2·복제 1)에 C1→C2→C3를 추가한다. consumer마다 별도 poll 스레드를 사용하고, AdminClient의 STABLE 상태·기대 멤버 수·배정과 각 client가 확인한 assignment의 일치를 기다린다. 모든 단계에서 P0·P1이 누락·중복 없이 한 번씩 배정되는지 확인했다.

| 멤버 수 | 실제 관찰 |
| --- | --- |
| 1 | C1=[0, 1] |
| 2 | C1=[0], C2=[1] |
| 3 | C1=[0], C2=[1], C3=[] |

실험 1건(세 단계)·ArchUnit 1건 통과, 실패·오류·skip 0, test Checkstyle 위반 0이다. 합친 명령은 **BUILD SUCCESSFUL**, 23초였다. 이전 전체 58건이나 offset reset 실험 2건을 이번 명령에서 재실행한 결과로 표현하지 않는다.

```shell
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home \
PATH=/opt/homebrew/opt/openjdk@21/bin:$PATH \
JAVA_TOOL_OPTIONS=-Dapi.version=1.44 \
./gradlew :apps:commerce-streamer:test --tests '*KafkaConsumerCountExperimentTest' \
  :apps:commerce-streamer:checkstyleTest --offline
```

이는 실제 subscribe·poll 기반 배정 실험이며 강제 assign/seek를 사용하지 않았다. topic은 비어 있고 업무 메시지·DB·offset commit·처리량·Spring listener factory를 검증하지 않는다. 업무용 partition 3개 계획과는 구분하며 업무용 consumer 수·group·설정을 바꾸지 않았다. 모든 client·스레드·실험 broker를 정리했다.

당시 결과는 선택 대기인 ADR 초안으로 정리했다. 이후 [Kafka 설정 비교 실험](kafka-settings-experiments.md#consumer-count-experiment)으로 옮기고 채택 전의 두 ADR 초안은 제거했다. ADR 0040 본문은 보존하고 상단에 실험 문서 링크를 남겼다. design.md와 AGENTS.md에도 실험 결과는 실험 문서에, 실제 채택한 결정은 ADR에 기록하는 진행 방식을 반영했다. 이 문서 분리는 새 테스트 실행 결과가 아니다.

<a id="failure-handling-experiment"></a>

## 13. DB 저장 실패 시 재시도와 소비 중단 비교

실험·선택 대기 기록을 [Kafka 설정 비교 실험](kafka-settings-experiments.md)에 모았다. 이어서 사용자의 요청으로 실패 처리 한 항목을 비교했다. `KafkaFailureHandlingExperimentTest`에서 실제 Kafka 3.5.1·MySQL 8.0과 Spring Kafka 3.3.4의 테스트 전용 container를 사용했다. 업무용 listener·factory·설정은 변경하지 않았다.

고유 topic(partition 1·복제 수 1)·group마다 committed offset을 0으로 명시적으로 준비했다. 주문 42를 offset 0, 주문 43을 offset 1에 저장했다. 공통 조건은 record listener·AckMode.RECORD·자동 commit 끔·동기 offset commit이다. 기존 parser·DB handler를 실제로 호출하고 주문 42의 두 번째 상품 저장 직전에 repository spy로 예외를 주입했다. 실제 DB를 끄지는 않았다.

DB 오류가 돌아온 직후 error handler로 넘기기 전 대기 지점에서, 첫 상품·완료 기록의 rollback과 DB 0행·committed offset 0·주문 43 미처리를 확인했다. 이후 기본 recoverer를 둔 DefaultErrorHandler(200ms·최대 2회 재시도)와 CommonContainerStoppingErrorHandler를 비교했다.

| 오류 조건 | 재시도 + 기본 후처리 | 중단 → 원인 제거 → 같은 group으로 명시적 재시작 |
| --- | --- | --- |
| 한 번만 실패 | 주문 42 (offset 0) → 같은 메시지 재수신 → 주문 43 (offset 1). 완료 2행·활동 4행·committed offset 2 | 중단 시 DB 0행·offset 0. 재시작 후 두 주문 저장·완료 2행·활동 4행·offset 2 |
| 주문 42가 계속 실패 | 주문 42 (offset 0) 세 번 실패 → 기본 recoverer가 offset 0 건너뜀 → 주문 43 (offset 1) 저장. 완료 1행·활동 2행·offset 2 | 중단 시 DB 0행·offset 0. 원인 제거·재시작 후 두 주문 저장·완료 2행·활동 4행·offset 2 |

지속 오류의 재시도 소진 뒤 `recovered` callback이 offset 0에 대해 호출되는 것도 확인했다. 이는 이번 기본 recoverer가 후처리했다는 의미이며 구매 활동 저장 성공이나 별도 실패 topic 전달이 아니다. 중단 방식의 재시작에서는 seek나 offset 재설정 없이 같은 record가 다시 전달됐다. 중단은 오류가 한 번으로 끝났어도 명시적인 재시작이 필요했다.

새 실험 2건(오류 조건마다 두 방식)·ArchUnit 1건이 통과했고 실패·오류·skip은 0건이다. test Checkstyle 위반 0, 합친 명령 **BUILD SUCCESSFUL**, 57초였다. 이전 전체 58건·reset 실험·consumer 수 실험을 다시 실행한 결과는 아니다. container·client·broker 정리와 DB 연결 풀 종료를 확인했다.

```shell
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home \
PATH=/opt/homebrew/opt/openjdk@21/bin:$PATH \
JAVA_TOOL_OPTIONS=-Dapi.version=1.44 \
./gradlew :apps:commerce-streamer:test --tests '*KafkaFailureHandlingExperimentTest' \
  :apps:commerce-streamer:checkstyleTest --offline
```

design.md·실험 문서에서 오류 주입 위치·rollback·같은 record 재수신·실제 committed offset·중단 범위와 검사 범위를 대조했다. 업무용 값은 선택 대기이며 새 ADR을 추가하지 않았다. DB commit 후 offset commit 전에 종료되는 재전달 실험, 영구 오류 분류·retry 소진 뒤 중단·별도 실패 topic·실제 DB 서버 중단은 검증하지 않았다.

<a id="db-commit-replay-experiment"></a>

## 14. DB commit 뒤 offset 미커밋 중단과 실제 재전달

사용자는 재전달 결과도 실험 문서의 결정 대기로 남기도록 요청했다. `KafkaReplayAfterDbCommitExperimentTest`를 추가해 실제 Kafka 3.5.1·MySQL 8.0·Spring Kafka 3.3.4에서 검증했다. 기존 실험의 DB 구성과 parser·handler를 사용하고 업무용 설정·listener는 변경하지 않았다.

고유 topic(partition 1·복제 수 1)·group의 committed offset을 준비 시 0으로 저장했다. 주문 42의 메시지를 한 번만 발행해 offset 0을 확인했다. 테스트용 container는 record·자동 commit 끔·동기 commit·배정 commit 끔이며 기존 DB handler가 성공해 반환한 뒤 Kafka listener 반환 전에 hook에서 대기했다. 별도 스레드에서 DB commit·TX 비활성·consumer position 1·committed offset 0을 확인했다.

hook 예외와 테스트용 중단 error handler로 첫 container가 완전히 종료되기를 기다렸다. DB 기록은 남고 offset은 0을 유지했다. 별도 container·consumer 객체를 같은 group으로 시작해 seek나 offset 재설정 없이 같은 메시지를 다시 받았다.

| 실제 관찰 | 결과 |
| --- | --- |
| 두 번 받은 record | 같은 topic·partition 0·offset 0·key 42·본문 |
| 첫 DB commit 뒤 / 첫 container 중단 뒤 / 재처리 뒤 committed offset | 0 → 0 → 1 |
| 첫 consumer / 재시작 consumer의 position | 1 / 1 |
| 완료 기록 | 기존 1행 유지, ID·사실 snapshot 불변 |
| 구매 활동 | 기존 2행 유지, ID·주문·상품·수량·확정 시각 불변 |
| 전체 저장 포트 호출 | 활동 2회·완료 기록 1회. 재전달의 추가 저장 없음 |
| Kafka end offset | 1 — 새 발행 없이 기존 record 재전달 |

새 실험 1건·ArchUnit 1건 통과, 실패·오류·skip 0, test Checkstyle 위반 0이다. 합친 명령은 **BUILD SUCCESSFUL**, 41초였다. 이전 전체 58건이나 다른 Kafka 실험을 다시 실행한 결과는 아니다. container·client·broker와 DB 연결 풀 종료를 확인했다.

```shell
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home \
PATH=/opt/homebrew/opt/openjdk@21/bin:$PATH \
JAVA_TOOL_OPTIONS=-Dapi.version=1.44 \
./gradlew :apps:commerce-streamer:test --tests '*KafkaReplayAfterDbCommitExperimentTest' \
  :apps:commerce-streamer:checkstyleTest --offline
```

이 검증은 DB commit 뒤 Kafka offset 미커밋 경계를 통제한 hook 실험이며 OS 강제 종료·broker 장애·rebalance 경쟁은 아니다. 같은 사실 no-op의 기존 결정을 유지하고 ack/error handler 등 업무용 값은 결정 대기로 남겼다. 새 ADR은 추가하지 않았고 ADR 0040의 본문은 보존한 채 상단 실험 링크 설명만 확장했다.

실험 문서의 기존 흐름도와 새 재전달 흐름도를 모두 왼쪽→오른쪽으로 정리했다. `42@0` 표기는 `주문 42 (offset 0)`으로 풀어 쓰고 offset이 DB 행 번호가 아닌 partition 안의 위치임을 명시했다. 추가 config 목록은 실험 후보만 정리했으며 아직 비교하거나 채택하지 않았다. design.md의 현재 상태·검증 범위와 링크를 함께 대조했다.

<a id="consumer-failure-policy"></a>

## 15. 메시지 오류 DLQ와 DB 재시도 소진 시 중단 정책 채택

**이 절은 횟수·간격을 정하기 전 오류 분기를 채택한 당시 기록이다. 이후 DB 재시도 최대 2회 선택은 [16절](#db-retry-count)에 추가했다.**

사용자가 오류별 처리 방향을 확인하고 채택했다. 메시지 자체 오류는 DLQ로 분리하고, 재시도 대상으로 분류한 일시적 DB 오류는 제한적으로 재시도한다. 소진 후에도 DB 오류가 계속되면 소비를 중단하고 실패 record를 지나가지 않도록 committed offset을 유지한다. 원인 해결 후 같은 group으로 다시 시작한다. DB 재시도 소진만으로 정상 메시지를 DLQ에 보내지 않는다.

재시도 횟수·간격·상세 예외 분류·중단 범위·재시작 주체는 미정이다. DLQ 발행 성공 전 원본 offset을 진행하지 않고 DLQ 전달과 업무 완료를 구분한다. DLQ topic·발행 실패 처리·관찰·재처리 경로도 아직 구체화·구현하지 않았다. 기존 실험의 2회·200ms를 업무용 값으로 채택하지 않았다.

[ADR 0041](../adr/0041-separate-invalid-messages-from-db-retry-exhaustion.md)을 Accepted로 추가하고 ADR 0040은 Accepted 본문을 보존한 채 메타데이터에 확장 관계를 표시했다. design.md·실험 문서의 오류 표·체크리스트에서 Retry→DLQ로 읽힐 수 있는 문장을 정리했다. 메시지 오류의 userId 예시는 실제 계약의 orderId로 맞췄고 기존 DB 검증 완료 항목과 남은 업무용 연결을 구분했다.

이번 변경은 정책 문서화다. Java·YAML·Compose를 변경하지 않았고 업무용 자동 구독·DLQ를 등록하지 않았다. 새 테스트를 실행하지 않았으며 기존 실험 결과·건수는 당시 기록으로 보존했다. 제한 재시도 후 중단 조합과 DLQ 경로의 실제 검증은 후속 작업이다. 문서 링크·코드 블록·ADR 날짜 미기록과 변경 공백을 확인한다.

<a id="db-retry-count"></a>

## 16. Consumer DB 저장 재시도 횟수를 최대 2회로 선택

**이 절은 대기 간격을 정하기 전의 횟수 선택 기록이다. 이후 1초·2초 대기와 기존 선택 반영은 [17절](#consumer-settings-adoption)에 추가했다.**

사용자는 최초 시도 이후 추가 재시도를 최대 2회로 선택했다. 최초 시도까지 최대 3번이며 중간에 성공하면 정상 처리한다. 세 번 모두 실패하면 ADR 0041의 소비 중단·offset 보호를 따르고, 원인 해결 후 같은 group으로 재시작한다. 이 수치는 consumer DB 저장에 대한 것으로 producer 전송 retries와 구분한다.

[ADR 0042](../adr/0042-use-two-db-retries-before-stopping-consumer.md)를 Accepted로 추가하고 ADR 0041은 본문을 보존한 채 메타데이터에 확장 관계만 표시했다. 대기 간격·상세 오류 분류·중단 범위·재시작 주체와 DLQ 세부 구성은 후속 선택이다. 기존 실험의 200ms·기본 skip은 채택하지 않았다.

현재 주차 design.md·실험 문서에서 횟수 미정이라는 현재 설명을 최대 2회 선택으로 맞췄다. 이전 실험 결과와 당시 정책 채택 기록은 당시 범위를 밝혀 보존했다. Java·YAML·Compose 변경이나 새 테스트 실행은 없으며 업무용 조합은 구현·검증 전이다. 문서 링크·코드 블록·ADR 날짜 미기록과 이전 본문 보존을 확인한다.

<a id="consumer-settings-adoption"></a>

## 17. 재시도 대기를 1초·2초로 선택하고 기존 사용자 결정을 반영

사용자는 첫 DB 재시도 전 1초, 둘째 전 2초 대기를 선택했다. 최대 2회 재시도와 소진 시 중단·offset 보호는 유지한다. 모두 실패하면 대기 합계는 3초이며 DB 처리·poll 등의 시간은 별도다. [ADR 0043](../adr/0043-increase-db-retry-delays-from-one-to-two-seconds.md)을 Accepted로 추가하고 ADR 0042 본문은 보존한 채 확장 링크만 표시했다. 새 조합은 아직 구현·실행하지 않았다.

사용자의 학습 기록에 이미 있던 earliest·업무용 partition 3개·같은 group 전체 활성 consumer 3개 선택도 [ADR 0044](../adr/0044-adopt-earliest-and-three-order-consumers.md)에 반영했다. 기존 설계·실험·학습 문서의 선택 대기 표기를 선택 완료·설정 적용 전으로 정리했다. 앱 인스턴스마다 concurrency=3으로 자동 확대하는 결정은 아니며 구체적인 배포 구성은 후속이다. ADR 0037·0040도 Accepted 본문을 보존하고 메타데이터에 후속 관계를 표시했다.

4번째 consumer·특정 처리율·static membership·producer batch 값은 계속 보류한다. group id·ack mode·예외 분류·중단 범위·재시작 주체와 DLQ 구성은 아직 미정이다. Java·YAML·Compose나 실제 topic·자동 구독은 변경하지 않았다. AGENTS.md에도 사용자의 명시적 선택을 이견 없이 확인했다면 반영하고 선택 완료·코드 적용·검증 상태를 구분하도록 기록했다.

AGENTS.md에 이 단계에서 추가했던 사용자 선택 반영 규칙 한 줄은 이후 사용자 요청으로 취소했다. 이전의 ADR 보존·날짜 미기록·실험 문서 분리 규칙과 이미 채택한 Kafka 설정은 유지한다.

테스트는 선택한 동작을 검증하고 성능 실험은 실제 부하에서 적합성을 판단하는 근거다. 이번 문서 변경에서는 새 테스트를 실행하지 않았으며 기존 실험의 200ms·기본 skip 결과는 당시 기록으로 보존했다. 새 ADR에 작업 날짜를 넣지 않았고 기존 ADR 본문·링크·코드 블록·변경 공백을 확인한다.

<a id="purchase-activity-group"></a>

## 18. 구매 활동 group 이름과 선택한 설정의 검증 상태 정리

사용자는 구매 활동 group.id를 purchase-activity로 선택했다. 같은 group 전체의 consumer 3개가 이 이름을 공유해 partition을 나눠 맡는다. [ADR 0045](../adr/0045-use-purchase-activity-consumer-group.md)를 Accepted로 추가하고 ADR 0044는 Accepted 본문을 보존한 채 메타데이터에 확장 관계만 표시했다.

선택 완료와 검증 완료를 구분해 [실험 문서의 상태 표](kafka-settings-experiments.md#selected-verification-status)에 정리했다. 기존 테스트는 earliest/reset, partition 2개 group 배정, 고정 200ms 재시도 후 기본 skip과 즉시 중단, DB 원자성·멱등성 및 DB commit 뒤 재전달을 확인했다. 선택한 1초/2초 재시도 후 중단 조합, 메시지 오류 DLQ, 업무용 partition 3개·consumer 3개·선택한 group 이름의 실제 연결, 복제 수 3·최소 ISR 2의 브로커 장애 동작은 아직 검증하지 않았다.

이름 자체에 별도 성능 실험이 필요한 것은 아니며 실제 listener 연결 시 유효 group.id와 멤버 구성을 확인한다. 공통 Kafka YAML의 loopers-default-consumer·demo 설정이나 Java 코드는 변경하지 않았고 실제 구독·새 테스트 실행도 하지 않았다. 기존 테스트 결과를 새 구성의 검증 완료로 바꾸지 않았다.

<a id="selected-settings-verification"></a>

## 19. 선택한 Kafka 설정의 격리 검증과 전체 회귀 검사

사용자가 선택했지만 그 값 그대로 검증하지 않았던 항목을 모두 테스트하도록 요청했다. 먼저 기존 책임·DB 경계·설정·관련 ADR을 확인한 뒤 테스트부터 추가했다. 이번 Java 변경은 테스트·공통 실험 지원 코드·복제 클러스터 fixture이며 업무용 main·YAML·Compose·자동 구독은 변경하지 않았다. AGENTS.md도 수정하지 않았다.

| 추가 테스트 | 실제 확인한 결과 |
| --- | --- |
| KafkaDbRetryPolicyExperimentTest 2건 | 처음 두 번 실패 후 세 번째 성공 / 세 번 실패 후 중단. 실제 대기 요청 1,000·2,000ms. 지속 실패의 완료 기록·활동·offset 모두 0, 정상 후속 주문도 미처리. 원인 제거·같은 group 재시작 뒤 완료 2·활동 4·offset 2 |
| KafkaDlqDeliveryExperimentTest 2건 | 실제 DLQ 발행 성공과 broker 크기 제한 거절. 성공 확인 직후 commit 전 offset 0·업무 행 0. 실패하면 DLQ 0건·offset 0, 복구·같은 group 재시작 후 원문·key·출처 header를 가진 DLQ 1건. 정상 후속 주문만 완료 1·활동 2, 원본 offset 2 |
| KafkaThreeConsumerPurchaseExperimentTest 1건 | 실제 purchase-activity group STABLE 멤버 3개·partition 3개 배정과 처리 멤버 일치. 주문 30개의 분포 P0 11·P1 12·P2 7. 같은 group의 새 container로 새 주문 1건만 이어 처리. 완료 31·활동 62·중복 수신 0·기존 행 ID 불변 |
| KafkaReplicationFailureExperimentTest 1건 | 실제 데이터 broker 3개·RF3·최소 ISR2·acks=all. follower 중단·leader 교체 후 성공, ISR 1 거절과 끝 위치 불변, ISR 2 복구 뒤 새 producer 성공. 기존 성공 기록 3건과 복구 후 1건을 정확히 읽음. 내부 offset topic 모든 partition RF3·ISR3·복구 뒤 commit 1 유지 |

재시도 성공의 실제 경과 간격은 1,041·2,070ms, 지속 실패는 1,041·2,075ms였다. 설정한 대기 외 poll·스케줄링 등의 시간이 포함된다. DB 저장 호출의 예외를 주입해 실제 MySQL rollback을 확인한 것이며 실제 DB 서버를 껐다는 의미는 아니다.

초기 실행에서는 재시도 관찰 코드가 Spring의 backoff 초기화 확인까지 대기로 집계했다. 관찰 지점을 실제 DefaultBackOffHandler 실행으로 옮겨 정확한 두 간격과 세 번의 시도 기대값을 유지했다. 작은 fixture heap에서 발생한 broker OutOfMemoryError는 테스트 heap 512MiB·cleaner 버퍼 16MiB로 수정했다. 실제 업무 설정이나 검사 기준을 완화하지 않았다.

복제 실험에서 ISR 미달 재시도 소진 뒤 같은 idempotent producer의 복구 전송이 OutOfOrderSequenceException으로 실패하는 실행도 관찰했다. 최종 broker 복구 검증은 새 producer로 분리했으며 기존 인스턴스의 자동 복구를 검증했다고 표현하지 않는다. 애플리케이션 재전송·producer 오류 후 복구 정책은 남은 판단이다.

### 실행 결과와 재현

JDK 21·Docker API 1.44·실제 Kafka broker 3.5.1·Kafka client 3.8.1·MySQL 8.0·Spring Kafka 3.3.4를 사용했다. 오류를 수정한 뒤 아래 전체 명령을 재실행했다.

| 검사 | 최신 결과 |
| --- | --- |
| commerce-streamer:check | 전체 70건·실패/오류/skip 0. 기존 64건과 새 6건을 함께 재실행, ArchUnit 포함 |
| modules:kafka:check | 5건·실패/오류/skip 0. main·test·testFixtures Checkstyle 통과 |
| streamer Checkstyle | main UP-TO-DATE·test 실행 통과, 보고서 위반 0 |
| streamer·Kafka 합친 명령 | BUILD SUCCESSFUL, 3분 36초 |
| commerce-api:check | 전체 523건·실패/오류/skip 0, ArchUnit 포함. main Checkstyle UP-TO-DATE·test 실행, 보고서 위반 0. BUILD SUCCESSFUL, 5분 12초 |
| 관련 전체 검사 합계 | API 523 + streamer 70 + Kafka 모듈 5 = 598건, 모두 통과 |

```shell
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home \
PATH=/opt/homebrew/opt/openjdk@21/bin:$PATH \
JAVA_TOOL_OPTIONS=-Dapi.version=1.44 \
./gradlew :apps:commerce-streamer:check :modules:kafka:check --offline
```

```shell
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home \
PATH=/opt/homebrew/opt/openjdk@21/bin:$PATH \
JAVA_TOOL_OPTIONS=-Dapi.version=1.44 \
./gradlew :apps:commerce-api:check --offline
```

API는 프로젝트 check가 정한 기능 테스트 전체를 실행했다. 별도 experimentTest로 분리한 이전 주차 PG 성능 비교는 이번 Kafka 검증 범위에 포함하지 않았다. 두 앱의 ArchUnit·관련 main/test와 Kafka fixture Checkstyle을 확인했다. 테스트용 Kafka·MySQL·Ryuk 종료 뒤 기존 사용자 컨테이너 5개만 실행 중임을 확인했다.

조건·각 단계의 실제 수치·코드 링크·한계는 [Kafka 설정 실험 5~8절](kafka-settings-experiments.md#selected-db-retry-experiment)에 모았다. 새 정책을 선택한 작업이 아니므로 새 ADR을 만들지 않았다. ADR 0041~0045는 결정 본문·당시 검증 기록을 보존하고 상단에 후속 검증 링크만 연결했다. 현재 설계의 상태도 실제 검증 범위와 맞춘다.

commerce-change-review 기준으로 이번 테스트·fixture의 정상·실패·중단·재시작·TX/offset 경계와 현재 설계·ADR을 대조했다. 발견한 관찰 코드·테스트 자원 문제를 수정했으며 이번 변경 범위에서 추가 수정이 필요한 계약 위반은 발견하지 않았다. 미정 정책과 검증 한계는 아래에 유지한다.

### 검증 범위·남은 연결

- 재시도·DLQ·3개 consumer 실험은 단일 broker/RF1과 실제 DB를 사용한다. 복제 장애 실험은 별도 데이터 broker 3개와 controller 1개이며 업무용 DB 저장을 포함하지 않는다. 모든 선택을 하나의 업무용 배포로 연결한 종단 검증은 아직이다.
- 데이터 broker 두 대를 중단해도 관리 quorum을 유지하려고 controller를 분리했다. 현재 로컬 Compose는 3대 모두 broker/controller를 겸하므로 두 대 중단 시 관리 quorum도 잃는다. controller 장애 허용성을 검증하거나 별도 controller 한 대를 업무용 구성으로 권장하는 실험이 아니다.
- AckMode.RECORD·동기 commit·배정 commit 끔, 테스트 container 전체 중단·DLQ 실패 시 중단은 관찰 조건이다. 업무용 ack·오류 분류·중단 범위·재시작 주체·DLQ 구성·운영 재처리는 미정으로 유지한다.
- 운영 처리량·lag·P95/P99·DB 수용량·네트워크/디스크 장애·controller 장애·DLQ 발행과 원본 commit 사이 강제 종료는 미검증이다. 보류했던 추가 값은 임의로 채택하지 않았다.

<a id="manual-immediate-adoption"></a>

## 20. MANUAL_IMMEDIATE 선택과 DB commit·ack 경계 검증

사용자는 기존 공통 batch의 MANUAL과 주문용 ack 선택을 구분한 뒤 MANUAL_IMMEDIATE를 시작값으로 선택했다. [ADR 0046](../adr/0046-acknowledge-order-records-with-manual-immediate.md)을 추가하고 ADR 0040·0041·0044·0045의 결정 본문을 보존한 채 후속 확장 링크만 표시했다. 모두 Accepted를 유지한다. ADR에는 작업 날짜를 넣지 않았다.

이번 변경은 새 격리 실험과 선택 기록이다. 기존 main·YAML·공통 factory·과거 RECORD 실험은 변경하지 않았다. 업무용 @KafkaListener·factory 등록은 남은 오류 처리 구성을 정한 뒤 진행한다. AGENTS.md도 수정하지 않았다.

### 실제 검증

KafkaManualAckExperimentTest에서 기존 parser·DB handler를 실제 Kafka·MySQL에 연결했다. 정상 listener는 DB handler가 정상 반환한 뒤 consumer 스레드에서 acknowledge()를 호출한다. 단일 partition/RF1·고유 group·earliest·자동 commit 끔·동기 commit·배정 commit 끔을 관찰 조건으로 사용했다.

| 사례 | 실행 수 | 관찰 결과 |
| --- | --- | --- |
| MANUAL / MANUAL_IMMEDIATE의 ack 전후 | 2건 | DB commit 뒤 ack 전에는 offset 0·완료 1·활동 2. ack 호출 직후 listener 반환 전에는 MANUAL=0·IMMEDIATE=1. listener 반환 뒤 둘 다 1. ack 시점에 DB TX 비활성 |
| 두 방식의 DB 실패·재시도 소진·재시작 | 2건 | 같은 offset 0을 최초 포함 3번 시도, 첫 1초·다음 2초 대기. 중단 시 ack 0·완료 0·활동 0·offset 0. 오류 제거·같은 group 재시작 뒤 42·43 저장, ack 2·완료 2·활동 4·offset 2 |
| IMMEDIATE의 DB commit 후 ack 전 중단 | 1건 | 같은 offset 0 두 번 수신, 동일 사실 no-op 후 ack. offset 0→0→1, 완료 1·활동 2·기존 행 ID/수량 유지, 활동 저장 호출 최초 2회뿐 |

새 실험 5건·ArchUnit 1건 통과, 실패·오류·skip 0이다. main Checkstyle UP-TO-DATE·test Checkstyle 실행 통과, 보고서 위반 0, BUILD SUCCESSFUL·1분 11초였다. 이전 전체 598건은 이번에 다시 실행하지 않았다. JDK 21·Docker API 1.44·broker 3.5.1·client 3.8.1·Spring Kafka 3.3.4·MySQL 8.0을 사용했다.

```shell
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home \
PATH=/opt/homebrew/opt/openjdk@21/bin:$PATH \
JAVA_TOOL_OPTIONS=-Dapi.version=1.44 \
./gradlew :apps:commerce-streamer:test --tests '*KafkaManualAckExperimentTest' \
  :apps:commerce-streamer:checkstyleMain :apps:commerce-streamer:checkstyleTest --offline
```

조건·흐름·실제 수치는 [실험 9절](kafka-settings-experiments.md#manual-ack-experiment)에 모았다. 실제 DB 서버 중단·OS 강제 종료·처리량·지연 우위를 측정한 결과는 아니다. DB commit과 ack 사이의 틈은 그대로 있으므로 기존 DB 멱등 처리가 계속 필요하다. 기존 DLQ 실험은 RECORD 조건이며 MANUAL_IMMEDIATE와 DLQ recoverer의 원본 offset 진행을 결합한 검증은 후속이다.

commerce-change-review 기준으로 새 테스트의 DB commit·ack·rollback·중단·동일 group 재시작·중복 no-op을 설계 및 ADR과 대조했다. 이번 테스트·문서 범위에서 추가 수정이 필요한 계약 위반은 발견하지 않았다. 상세 예외 분류·중단 범위·재시작 주체·DLQ 구성은 미정으로 유지한다.

<a id="conflicting-fact-comparison"></a>

## 21. 같은 이벤트의 수량 충돌: DLQ 분리와 소비 중단 비교

사용자는 ack 선택 뒤 다음 항목을 이어 진행하도록 요청했다. 이미 채택한 메시지 형식 오류 DLQ·일시적 DB 오류 제한 재시도는 유지하고, ADR 0041에서 미뤘던 동일 식별자의 다른 사실 충돌 한 항목을 비교했다. 업무용 분류는 선택 대기로 남겼으며 새 ADR이나 main·YAML·Compose·업무용 factory를 추가하지 않았다.

KafkaConflictingFactExperimentTest를 추가했다. 실제 Kafka·MySQL·기존 parser/handler, MANUAL_IMMEDIATE·자동 commit 끔·동기 commit을 사용한다. 테스트는 고유 단일 partition/RF1 topic과 group에서 정상 주문 42, 같은 eventId의 수량 충돌 record, 정상 주문 43을 차례로 발행했다. 충돌은 기존 상품 7의 수량 5를 6으로 바꿔 의도적으로 만들었다.

| 후보 | 실제 결과 |
| --- | --- |
| 충돌 DLQ 후 계속 처리 | 기존 42의 수량·활동 행 ID·완료 ID 유지. DLQ 1건의 원문·key·원본 topic/partition/offset/group 일치. DLQ 성공 확인 전후 commit 경계를 1→2로 확인하고, 43 저장 후 3으로 진행. 정상 ack 2회·완료 기록 2·활동 4 |
| 소비 중단 후 조사 | 기존 42 유지·43 미처리·위치 1·DLQ 0. 원인 해결 없이 같은 group 재시작하면 같은 offset 1의 충돌로 다시 멈춤. 정상 ack 1회·완료 기록 1·활동 2·중단 2회 |

DLQ 후보는 해당 충돌 예외만 첫 실패에서 recover하며, MANUAL_IMMEDIATE의 원본 위치 진행에 필요한 commitRecovered=true와 발행 성공 확인을 테스트에 연결했다. 예상 밖 오류와 DLQ 발행 실패의 중단은 실험을 드러내는 조건이며 업무용 정책으로 채택하지 않았다. 같은 eventId라 하더라도 record 위치는 서로 다르며, DLQ 보존과 DB 업무 완료를 구분했다.

새 비교 2건·기존 ack 경계/실패/재전달 5건·ArchUnit 1건, 총 8건 통과, 실패·오류·skip 0이다. main Checkstyle UP-TO-DATE·test Checkstyle 실행 통과·보고서 위반 0, BUILD SUCCESSFUL·1분 32초였다. 이전 전체 598건은 다시 실행하지 않았다.

```shell
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home \
PATH=/opt/homebrew/opt/openjdk@21/bin:$PATH \
JAVA_TOOL_OPTIONS=-Dapi.version=1.44 \
./gradlew :apps:commerce-streamer:test --tests '*KafkaConflictingFactExperimentTest' \
  --tests '*KafkaManualAckExperimentTest' :apps:commerce-streamer:checkstyleMain \
  :apps:commerce-streamer:checkstyleTest --offline
```

조건·각 단계의 관찰 결과·선택 기준은 [실험 10절](kafka-settings-experiments.md#conflicting-fact-experiment)에 모았다. commerce-change-review 기준으로 실험 입력·기존 DB 보호·정상 ack와 recovery commit·중단/재시작 경계를 확인했다. 이번 범위에서 추가 수정이 필요한 계약 위반은 발견하지 않았다. DLQ 발행 실패·여러 partition·다른 충돌 조건·운영 재처리는 미검증으로 남겼다.

AGENTS.md와 기존 ADR의 본문도 변경하지 않았다. 사용자의 업무용 선택 뒤 결정·근거·대안을 새 ADR로 남길 예정이다.

<a id="ack-adapter-implementation"></a>

## 22. 수신 어댑터에 DB 처리 후 ack 경로 구현

사용자는 정책 선택·격리 실험 뒤 실제 구현을 이어 가도록 했다. 이미 채택한 ADR 0046의 DB commit/no-op 후 수동 ack를 작은 단위로 먼저 연결했다. 새 정책을 고르지 않았으므로 새 ADR은 만들지 않았다. ADR 0046의 결정 본문은 보존하고 상단에 구현 링크만 추가했다.

OrderConfirmedRecordAdapter에 handle(record, acknowledgment)를 추가했다. ack 인자를 먼저 확인하고 기존 handle(record)의 parser·application handler를 호출한 뒤 acknowledge()를 실행한다. DB handler의 새 TX가 commit되거나 동일 사실 no-op으로 정상 반환한 뒤에만 ack에 도달한다. 파싱·DB 오류와 ack 호출 오류는 잡아 숨기지 않는다. ack가 없으면 DB 작업 전에 실패하며, ack가 실패해도 이미 commit된 DB 효과는 유지된다.

기존 ack 없는 handle(record)는 직접 파싱·DB 처리 호출의 호환성을 유지한다. main의 domain·application·DB TX·재시도·DLQ·factory·YAML·자동 구독은 변경하지 않았다. 실제 주문용 factory·@KafkaListener의 등록은 후속이다.

### 테스트와 검토

먼저 단위 테스트 5건을 추가하고 Kafka 수동 ack 실험을 새 두 인자 경로로 바꿨다. 구현 전 compileTestJava가 해당 메서드 부재로 실패하는 것을 확인한 뒤 최소 구현했다. 충돌 비교 실험도 같은 어댑터 경로를 사용하도록 연결했다. 기존 기대값과 ArchUnit·Checkstyle 규칙은 유지했다.

| 검사 | 실제 검증 범위·결과 |
| --- | --- |
| OrderConfirmedRecordAdapterTest 8건 | 기존 3건과 새 5건. handler 정상 반환 뒤 ack 순서, 파싱/DB 실패 시 미호출, ack 예외 전파, ack 누락 시 업무 호출 전 실패. handler·ack 대역을 사용한 단위 검증 |
| KafkaManualAckExperimentTest 5건 | 실제 Kafka·MySQL과 새 main 어댑터의 ack 경계 2·DB 실패/재시작 2·no-op 재전달 1. MANUAL의 ack 직후 위치 0·IMMEDIATE 1, DB 실패 세 번 뒤 ack 0·행 0·위치 0, 재시작 뒤 위치 2. DB commit 후 ack 전 중단의 재전달에서는 기존 행 유지·위치 0→0→1 |
| KafkaConflictingFactExperimentTest 2건 | 같은 main 어댑터로 DLQ 후보·중단 후보 회귀. 충돌은 정상 ack하지 않고 기존 사실 유지. 두 후보 결과는 21절과 동일하며 업무용 분류는 계속 선택 대기 |
| ArchUnit 1건 | interfaces→application의 기존 의존 방향 유지, 통과 |
| Checkstyle main/test | 모두 실행 통과·보고서 위반 0 |
| 합계·명령 | 16건·실패/오류/skip 0, BUILD SUCCESSFUL·1분 32초 |

```shell
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home \
PATH=/opt/homebrew/opt/openjdk@21/bin:$PATH \
JAVA_TOOL_OPTIONS=-Dapi.version=1.44 \
./gradlew :apps:commerce-streamer:test --tests '*OrderConfirmedRecordAdapterTest' \
  --tests '*KafkaManualAckExperimentTest' --tests '*KafkaConflictingFactExperimentTest' \
  :apps:commerce-streamer:checkstyleMain :apps:commerce-streamer:checkstyleTest --offline
```

단위 테스트의 ack 실패는 대역 예외이고 Kafka 재전달은 DB commit 후 ack 전의 통제된 hook 예외다. 실제 broker offset commit 장애·OS 강제 종료를 새로 재현한 결과가 아니다. 이전 전체 598건과 다른 Kafka 실험은 이번에 재실행하지 않았다. 고유 topic/group의 격리 broker·MySQL을 사용했다.

commerce-change-review 기준으로 정상·실패·no-op·commit/ack 경계·예외 전파·기존 한 인자 호출 호환성과 현재 주차 문서를 대조했다. 이번 변경 범위에서 추가 수정이 필요한 계약 위반은 발견하지 않았다. 실제 업무용 listener/factory 연결·오류 분류·DLQ 실패 대응·재시작 주체는 남겨 둔다.

<a id="order-listener-wiring"></a>

## 23. 조건부 주문용 listener·factory 연결과 실제 정상/중복 수신

사용자가 구현을 이어 가도록 했다. DLQ 발행 실패 시의 업무용 대응은 질문·선택 대기로 남기고, 선택이 완료된 record 수신·MANUAL_IMMEDIATE·earliest·group 이름과 기존 어댑터를 실제 listener/factory에 연결했다. 기존 자동 구독 없는 기본 상태를 유지한다. 새 정책을 채택한 것이 아니므로 새 ADR 없이 관련 ADR의 결정 본문을 보존하고 구현 링크만 연결했다.

| 변경 | 구현한 책임 |
| --- | --- |
| OrderConfirmedKafkaConfiguration | 별도 String/byte[] consumer factory·record listener factory. MI·earliest·자동 commit 끔·consumer의 topic 자동 생성 요청 끔·동기 commit·배정 commit 끔. 공유 batch factory 유지 |
| OrderConfirmedKafkaListener | 실제 @KafkaListener가 어댑터 handle(record, acknowledgment)를 동기 호출. group=purchase-activity, 기본 topic=order-events, ID=order-confirmed-purchase-activity |
| application.yml | commerce.order-events.topic=order-events·consumer.enabled=false. 앱별 concurrency 기본값은 두지 않음 |
| 활성화 조건 | enabled=true일 때 전용 orderConfirmedKafkaErrorHandler bean과 앱별 concurrency 필수. 준비 없이 framework 기본 recoverer로 소비하지 않도록 누락 시 시작 실패 |

전체 group 목표 3개와 앱 인스턴스별 concurrency는 구분한다. 이번에 배포 인스턴스 수나 앱별 3개를 새로 채택하지 않았다. consumer.auto-startup은 enabled 상태에서 기본 true이며 테스트에서는 false로 등록 후 직접 시작한다. 기존 공통 KafkaConfig·kafka.yml·demo와 broker/Compose·producer는 변경하지 않았다.

### 테스트·실행 결과

먼저 설정·구독 테스트를 추가하고 신규 클래스 부재로 compileTestJava가 실패하는 것을 확인한 뒤 구현했다. 실제 Kafka 통합 검증은 기존 DB 준비 구성을 재사용하고 새 주문용 factory·@KafkaListener를 직접 로드했다.

| 검사 | 실제 검증 범위·결과 |
| --- | --- |
| OrderConfirmedKafkaConfigurationTest 3건 | 선택한 실효 consumer 값·record/MI·topic/group·parent concurrency 3 설정 바인딩과 공통 batch MANUAL 보존. 전용 오류 처리기 누락·앱별 수 누락 시 시작 실패. consumer는 auto-startup=false여서 실제 broker 처리량·3-member 실행 검증은 아님 |
| ConsumerPreparationConfigurationTest 2건 | 실제 local/test YAML과 신규 두 클래스를 포함해 기본 flag=false·주문용 bean 부재·자동 구독 0개 |
| OrderConfirmedKafkaListenerIntegrationTest 1건 | 실제 Kafka/MySQL·단일 partition/RF1·테스트 concurrency=1·purchase-activity. 정상 사실을 두 번 발행한 서로 다른 record(offset 0·1)를 실제 annotation listener가 처리. 완료 1·활동 2·기존 ID 유지, group 위치 0→1→2 |
| OrderConfirmedRecordAdapterTest 8건 | 파싱·DB 처리 후 ack·실패 시 미호출·ack 예외 전파·ack 누락 보호 단위 회귀 |
| KafkaManualAckExperimentTest 5건 | 기존 실제 Kafka/MySQL의 ack 경계·DB 실패 재시작·같은 offset의 no-op 재전달 회귀 |
| ArchUnit 1건 | 기존 계층 의존 방향 통과 |
| Checkstyle main/test | 모두 실행 통과·보고서 위반 0 |
| 합계·명령 | 20건·실패/오류/skip 0, BUILD SUCCESSFUL·1분 20초 |

```shell
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home \
PATH=/opt/homebrew/opt/openjdk@21/bin:$PATH \
JAVA_TOOL_OPTIONS=-Dapi.version=1.44 \
./gradlew :apps:commerce-streamer:test --tests '*OrderConfirmedKafka*Test' \
  --tests '*ConsumerPreparationConfigurationTest' --tests '*OrderConfirmedRecordAdapterTest' \
  --tests '*KafkaManualAckExperimentTest' :apps:commerce-streamer:checkstyleMain \
  :apps:commerce-streamer:checkstyleTest --offline
```

통합 검증의 오류 처리기는 테스트 전용 CommonContainerStoppingErrorHandler bean이다. 이 테스트는 정상·논리적 중복만 검증하며 해당 중단 handler를 업무용 정책으로 채택한 것은 아니다. 새 annotation listener의 동일 offset 재전달·업무용 retry/DLQ/중단 결합, 운영 3-consumer 배포는 아직 검증하지 않았다. 테스트의 동일 사실 두 번째 발행(offset 1)을 Kafka가 같은 record를 재전달한 것으로 표현하지 않는다. 이전 전체 598건과 다른 실험은 이번에 재실행하지 않았다.

commerce-change-review 기준으로 새 설정의 default 비활성·전용 오류 처리기 요구·전용 consumer 설정·DB/ack 경계·기존 batch 보존·계층 의존을 검토했다. 이번 연결 범위에서 추가 수정이 필요한 계약 위반은 발견하지 않았다. DLQ 실패 시 중단/자동 재시도, 상세 오류 분류·중단 범위·재시작 주체·DLQ topic은 사용자 선택·연결 전이다.

<a id="conflicting-fact-adoption"></a>

## 24. 사실 충돌의 DLQ 분리 방향을 결정으로 반영

사용자는 충돌 비교 결과와 DLQ 분리·정상 주문 계속 처리 권고를 확인하고 동의한 뒤 실제 구현을 이어 가도록 했다. 이전 비교 문서의 선택 대기 표기를 정리하고 ADR 0047을 Accepted로 추가했다. 기존 ADR 0039·0041은 결정 본문을 보존한 채 상단에 확장 관계와 후속 링크만 추가했으며 Accepted를 유지했다.

같은 식별자의 다른 사실은 기존 DB를 유지하고 DLQ로 분리한다. 이 충돌을 일시적 DB 오류의 재시도 대상으로 삼지 않으며 DLQ 성공 확인 뒤에만 원본 위치를 진행한다. 동일 사실 재전달의 no-op·정상 ack와 DB 재시도 소진 시 중단 정책은 유지한다.

이 절은 선택을 결정으로 반영한 기록이며 새 테스트 실행이 아니다. 근거는 21절의 두 후보 비교와 22절의 어댑터 회귀다. 23절의 실제 listener 정상/논리적 중복 검증을 업무용 DLQ 분류 적용 결과로 확대하지 않는다. 전용 업무용 error handler·DLQ topic과 운영 경로는 아직 연결하지 않았다. DLQ 전송 자체가 실패했을 때 중단할지 자동 재시도할지는 별도 질문·선택 대기로 유지한다.

<a id="dlq-failure-manual-recovery"></a>

## 25. DLQ 전송 실패 시 중단·사람의 복구 후 재시작 선택

사용자는 DLQ 전송마저 실패하면 사람이 수동으로 대응하는 방향을 선택했다. 사람이 원인을 확인·수정하고 같은 group으로 소비를 재시작하면 consumer가 보존된 원본 record를 다시 읽어 DLQ 전송을 재시도한다. 사람에게 메시지 수작업 복사나 offset 건너뛰기를 기본 절차로 요구하는 뜻은 아니다.

원본 offset을 보호하고 소비를 중단하며 자동 소비 재개 루프를 추가하지 않는다. 실패 원인과 원본 topic/partition/offset/group 등 복구 정보를 확인할 수 있게 한다. 기존 producer 내부 전송 재시도·timeout과 DB 재시도 횟수·간격은 유지한다. 중단 범위·배포 재시작 정책과 DB 재시도 소진 후 재개 주체는 이번 결정으로 미리 확정하지 않는다.

ADR 0048을 Accepted로 추가하고 ADR 0041·0047은 본문을 보존한 채 후속 확장 링크만 추가했다. 기존 결정은 Accepted를 유지하며 작업 날짜를 기록하지 않았다. 현재 설계·실험 상태 표를 선택 완료·업무용 적용 전으로 맞췄다.

이번 변경은 정책 문서화이며 Java·YAML·자동 구독을 변경하거나 새 테스트를 실행하지 않았다. 기존 DLQ 실패 실험은 RECORD에서 실제 크기 제한 거절·원본 offset 0·중단과 제한 복구 후 같은 group 재시작을 확인한 근거다. 실제 주문용 MANUAL_IMMEDIATE listener와 업무용 오류 처리기를 결합한 실패/수동 재시작은 아직 검증하지 않았다. 최근 20건 검사는 23절의 당시 실행으로 유지한다.

문서의 상대 링크·코드 블록·ADR 날짜 미기록과 이전 ADR 본문 보존을 확인한다.

<a id="consumer-error-handler"></a>

## 26. 주문용 error handler 연결과 실제 listener의 실패 경로 검증

사용자가 ADR 0041·0047·0048에서 미뤄 둔 세 가지를 선택했다. 메시지 오류·사실 충돌 외의 모든 예외를 DB 재시도 대상으로 보고, 중단 범위는 주문 listener 전체, DLQ는 `{topic}-dlt`에 원본 key로 partition을 정한다. [ADR 0049](../adr/0049-connect-order-consumer-error-handler.md)를 추가하고 ADR 0041·0047·0048은 본문을 보존한 채 상태·후속 링크만 갱신했다. 모두 Accepted를 유지한다.

### 구현

| 변경 | 책임 |
| --- | --- |
| `OrderConfirmedKafkaErrorHandling` | 주문용 listener가 켜질 때만 `orderConfirmedKafkaErrorHandler`와 DLQ용 producer(String/byte[])를 등록. `DefaultErrorHandler` + 1초·2초 두 번 재시도 + recoverer. recoverer는 메시지 문제면 DLQ로 보내고, 그 밖이거나 DLQ 전송이 실패하면 listener 전체를 멈춘다. `commitRecovered=true` |
| `InvalidOrderConfirmedMessageException` | adapter가 parser의 `IllegalArgumentException`을 감싸 메시지 오류를 구분 |
| `ConflictingOrderConfirmationException` | `OrderConfirmationHandler.handle(command)`가 domain 충돌 예외를 감싸 수신 계층에 드러냄. interfaces→domain 의존 금지 규칙 유지 |

처음에는 수신 계층에서 domain의 충돌 예외를 직접 참조해 ArchUnit이 실패했다. 규칙을 완화하지 않고 application 예외로 감싸도록 바꿨다. 충돌 비교 실험(21절)의 catch도 같은 이유로 새 application 예외로 맞췄으며, 기대값은 바꾸지 않았다. adapter 단위 테스트의 메시지 오류 기대값은 `IllegalArgumentException`에서 하위 타입 `InvalidOrderConfirmedMessageException`으로 좁혔다.

DLQ 전송 결과 대기는 10초다. 기다리는 동안 consumer 스레드가 멈추므로 `max.poll.interval.ms` 2분보다 짧게 두었다. 업무용 활성화 flag(`consumer.enabled=false`)와 YAML·Compose는 바꾸지 않았다.

위 문장은 당시의 해석이다. 이후 [36절](#dlt-timeout-verification)에서 10초가 최소 결과 대기이고 실제 예산은 125초, 주문용 poll 제한은 기본 5분임을 확인했다. 이 과거 기록은 보존하고 현재 설명을 설계에 정정했다.

### 실제 검증

`OrderConfirmedKafkaErrorHandlingIntegrationTest`는 실제 Kafka 3.5.1·MySQL 8.0, 실제 `@KafkaListener`·주문용 factory·새 오류 처리기를 쓴다. 고유 topic(partition 1·RF1)과 같은 이름의 `-dlt` topic, group `purchase-activity`, concurrency 1이다. 한 테스트에서 세 단계를 이어서 확인했다.

| 단계 | 입력 (offset) | 관찰 결과 |
| --- | --- | --- |
| 1. 메시지 문제 | 정상 42 (0), 수량을 바꾼 42 (1), 형식 오류 key 99 (2), 정상 43 (3) | DLQ에 원본 offset 1·2 두 건(원본 topic·group header 일치). 42·43 저장, 42의 수량 5·3 유지. committed offset 4 |
| 2. DB 오류 | 44 저장 시 예외 주입 (4), 정상 45 (5) | 44를 세 번 시도(간격 1초 이상·2초 이상) 뒤 listener 중단. committed offset 4 유지, 44·45 미저장, DLQ 추가 없음. 예외 제거 후 같은 group으로 재시작 → 44·45 저장, offset 6 |
| 3. DLQ 전송 실패 | DLQ `max.message.bytes=1024`로 거절, 형식 오류 key 98 (6), 정상 46 (7) | listener 중단. committed offset 6 유지, DLQ 추가 없음, 46 미저장. 크기 제한 복구 후 재시작 → DLQ에 원본 offset 6 추가, 46 저장, offset 8 |

| 검사 | 결과 |
| --- | --- |
| 새 통합 테스트 1건 + 관련 회귀 (adapter 8·설정 3·준비 2·기존 listener 1·충돌 실험 2·recorder 16·ArchUnit 1) | 34건 통과, 실패·오류·skip 0 |
| `commerce-streamer:check` 전체 | 87건 통과, 실패·오류·skip 0. Checkstyle main/test 위반 0. BUILD SUCCESSFUL, 5분 12초 |

```shell
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home \
PATH=/opt/homebrew/opt/openjdk@21/bin:$PATH \
JAVA_TOOL_OPTIONS=-Dapi.version=1.44 \
./gradlew :apps:commerce-streamer:check --offline
```

DB 오류는 저장 포트에 예외를 주입한 것이며 실제 DB 서버를 끄지 않았다. DLQ 실패는 broker의 크기 제한 거절이다. concurrency 3에서 listener 전체가 함께 멈추는 동작, DLQ 전송 확인 뒤 원본 commit 전 강제 종료, 여러 앱 인스턴스의 재배정은 검증하지 않았다. commerce-api와 Kafka 모듈 검사는 이번에 다시 실행하지 않았다.

<a id="local-topics-and-activation"></a>

## 27. 로컬 topic 생성과 local profile 활성화

사용자가 앞서 고른 topic·브로커 설정과 함께 DLQ topic(partition 1·복제 3·min ISR 2), streamer 1대의 concurrency 3, local profile에서만 발행·소비 활성화를 선택했다. [ADR 0050](../adr/0050-create-order-topics-in-compose-and-enable-local-events.md)을 추가하고 ADR 0037·0040·0044에는 후속 링크만 추가했다(Accepted 유지).

변경: compose 브로커 3대에 자동 생성 끔·offset topic 복제 3, `kafka-init` 서비스 추가. commerce-api `local`에 `commerce.order-events.enabled=true`, commerce-streamer `local`에 `consumer.enabled=true`·`concurrency=3`. `ConsumerPreparationConfigurationTest`는 local profile이 구독 0개라는 이전 기대값을 새 결정에 맞춰 바꿨다. test profile은 구독 0개, local profile은 주문 listener 1개·concurrency 3·전용 오류 처리기 등록을 확인한다.

| 검증 | 결과 |
| --- | --- |
| 새로 띄운 로컬 클러스터의 init 출력 | `order-events` partition 3·복제 3·min ISR 2·ISR 3개, `order-events-dlt` partition 1·복제 3·min ISR 2. init exit 0 |
| 세 브로커 실효 설정 (`kafka-configs --describe --all`) | `auto.create.topics.enable=false`, `offsets.topic.replication.factor=3` |
| 없는 topic `order-evnets`로 console producer 전송 | metadata timeout(UNKNOWN_TOPIC_OR_PARTITION), topic 목록은 두 업무 topic뿐 |
| streamer 준비 설정 2·주문 설정 3·ArchUnit 1, test Checkstyle | 6건 통과, 위반 0 |

`__consumer_offsets`는 아직 만들어지지 않았다(streamer 첫 실행 때 생성). 그 복제 수, 두 앱을 local profile로 띄운 실제 처리, commerce-api 테스트는 이번에 확인하지 않았다.

<a id="w6-delivery-cases"></a>

## 28. 두 앱을 잇는 W6 발행 사례 2건 (W7 Outbox 비교용)

과제의 "미발행 1건·정상 발행·소비 1건"을 로컬 브로커 3대(ADR 0050 설정)에서 두 단계로 재현했다. 두 앱은 DB가 따로이므로 한 JVM에서 묶지 않고, 같은 broker topic을 사이에 둔다. 두 테스트는 `@Tag("experiment")`라 평소 `test`·`check`에서 빠진다. commerce-streamer에도 commerce-api와 같은 `experimentTest` task를 추가했다.

| 단계 | 테스트 | 하는 일 |
| --- | --- | --- |
| 1 | commerce-api `W6DeliveryCasesPublishTest` | `order-events-w6-cases`(partition 3·복제 3·min ISR 2)를 새로 만든다. 고정 Clock `2026-10-30T01:00:00.123456Z`, 기존 주문 API·로컬 PG fixture·실제 PG 결과 적용(`reconcile`)으로 두 주문을 확정한다. A(양말 2·모자 1)는 send 직전 hook(`StopBeforeSend`)에서 멈추고, B(양말 1)는 실제로 보낸다 |
| 2 | commerce-streamer `W6DeliveryCasesConsumeTest` | 같은 topic을 실제 주문 listener(group purchase-activity·concurrency 3·업무용 오류 처리기)로 소비한다. 모든 partition의 committed offset이 끝까지 가면 DB를 확인하고, 성공하면 사례 topic을 지운다 |

```shell
docker compose -f docker/kafka-cluster-compose.yml up -d
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home \
PATH=/opt/homebrew/opt/openjdk@21/bin:$PATH \
JAVA_TOOL_OPTIONS=-Dapi.version=1.44 \
./gradlew :apps:commerce-api:experimentTest --tests '*W6DeliveryCasesPublishTest' --offline
# 이어서 같은 환경 변수로
./gradlew :apps:commerce-streamer:experimentTest --tests '*W6DeliveryCasesConsumeTest' --offline
```

### W6 원래 실행의 관찰값

기준 코드: 이 기록이 들어 있는 커밋의 W6 코드. ID는 테스트 전용 MySQL에서 생성된 값이며 실행마다 다시 만들어진다.

| 사실 | 사례 A (미발행) | 사례 B (정상 발행·소비) |
| --- | --- | --- |
| orderId | 1 | 2 |
| providerOrderId | LP-ORD-0000000001 | LP-ORD-0000000002 |
| transactionKey | TEST-TX-1 | TEST-TX-2 |
| eventId | order-confirmed:v1:1 | order-confirmed:v1:2 |
| confirmedAt | 2026-10-30T01:00:00.123456Z | 2026-10-30T01:00:00.123456Z |
| items | productId 1 × 2, productId 2 × 1 | productId 1 × 1 |
| 주문 / 예약 | CONFIRMED / CONSUMED | CONFIRMED / CONSUMED |
| broker | 그 key의 record 없음 | `order-events-w6-cases` partition 2·offset 0 |
| 구매 활동 (streamer DB) | 없음 | 완료 기록 `order-confirmed:v1:2` 1행, 활동 (2, 1, 1, 2026-10-30T01:00:00.123456Z) 1행 |
| committed offset (purchase-activity) | — | p0=0, p1=0, p2=1 (end offset과 같음) |

A는 DB에서 확정됐지만 broker와 구매 활동 어디에도 없다. DB commit과 broker 전송이 원자적으로 묶이지 않았다는 증거이며 W7 Outbox가 메울 구간이다. hook은 send 호출을 건너뛴 통제된 중단이며 OS 강제 종료가 아니다.

함께 확인한 것:
- 2단계에서 consumer group이 처음 쓰이면서 만들어진 `__consumer_offsets`가 partition 50·ReplicationFactor 3이었다(27절에서 남긴 확인).
- 두 단계 모두 BUILD SUCCESSFUL(1분 5초, 31초). 테스트용 MySQL은 Testcontainers가 정리했고, 사례 topic은 2단계 끝에서 지웠다. 업무용 `order-events`·`order-events-dlt`에는 아무것도 쓰지 않았다.
- api·streamer test Checkstyle 위반 0.

같이 고친 것: commerce-streamer의 `spring.application.name`이 템플릿에서 온 `commerce-api`였다. Kafka client-id·metric tag·Slack 알림 이름에 쓰이므로 `commerce-streamer`로 바꿨다.

W7에서는 새 격리 실행으로 새 ID를 얻는다. 위 숫자에 맞추려고 행을 만들거나 지우지 않는다.

<a id="producer-settings"></a>

## 29. producer metadata 대기 5초·retries 기본, 전체 회귀

사용자가 `max.block.ms`를 5초로 줄이고 `retries: 3`을 지워 클라이언트 기본에 두기로 했다. [ADR 0051](../adr/0051-limit-producer-metadata-wait-and-keep-default-retries.md)을 추가하고 ADR 0035에 후속 링크만 추가했다(Accepted 유지). `KafkaClientConfigurationTest`에 local·test profile의 producer 설정 검사(`max.block.ms=5000`, `retries` 없음)를 먼저 추가해 실패를 확인한 뒤 `kafka.yml`을 바꿨다.

| 검사 (한 명령) | 결과 |
| --- | --- |
| `commerce-api:check` | 523건 통과, 실패·오류·skip 0. ArchUnit 포함 |
| `commerce-streamer:check` | 87건 통과, 실패·오류·skip 0. ArchUnit 포함 |
| `modules:kafka:check` | 7건 통과(새 producer 설정 2건 포함), 실패·오류·skip 0 |
| Checkstyle (api main/test, streamer main/test, kafka main/test/testFixtures) | 위반 0 |
| 합친 명령 | BUILD SUCCESSFUL, 30분 |

```shell
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home \
PATH=/opt/homebrew/opt/openjdk@21/bin:$PATH \
JAVA_TOOL_OPTIONS=-Dapi.version=1.44 \
./gradlew :apps:commerce-api:check :apps:commerce-streamer:check :modules:kafka:check --offline
```

`experiment` tag 테스트(28절의 두 사례, API의 이전 PG 실험)는 이 명령에 포함되지 않는다. 28절 사례는 producer 설정을 바꾸기 전에 실행했다. 브로커 전체 장애에서 5초 안에 실패하는지, 브로커 1대 재시작을 재시도로 넘기는지는 실행으로 확인하지 않았다.

<a id="explicit-partition-probe"></a>

## 30. consumer 수 실험에 partition별 record 수신 확인 추가

과제 5번의 "두 partition에 명시적으로 테스트 record를 보내 읽히는 것을 확인"이 빠져 있었다. 12절의 실험은 빈 topic에서 배정만 관찰했다. `KafkaConsumerCountExperimentTest`에서 멤버가 2개로 안정된 뒤 P0·P1에 partition을 지정해 record를 하나씩 보내고, 각 멤버가 자기 partition의 record만 정확히 하나 읽었는지 기다려 확인한다. 배정 직후 position이 정해지기 전에 보낸 record를 놓치지 않도록 멤버의 `auto.offset.reset`을 earliest로 두었다(topic은 처음에 비어 있다). 기존 배정 기대값은 그대로다.

| 단계 | 실제 관찰 |
| --- | --- |
| 멤버 1 | C1=[0, 1] |
| 멤버 2 | C1=[0], C2=[1] |
| 멤버 2, probe 송신 | send metadata P0·P1. C1이 `0:to-p0`, C2가 `1:to-p1`만 수신 |
| 멤버 3 | C1=[0], C2=[1], C3=[] |

실험 1건과 test Checkstyle 통과, BUILD SUCCESSFUL 27초. 격리 topic에만 썼고 업무 topic·DB는 쓰지 않았다. 같은 명령에서 다른 테스트는 실행하지 않았다.

design.md에는 이 결과와 함께 세 가지 이유를 보탰다: 주문용 factory가 fetch 기본값을 쓰는 이유, 메시지 문제를 DLQ로 빼도 되는 이유(주문당 이벤트 1건, 순서 의존 없음), earliest에서 offset이 만료되면 다시 읽는 부하.

<a id="db-driver-timeouts"></a>

## 31. MySQL 드라이버 timeout과 전체 회귀

응답 없는 DB에서는 드라이버가 기본값(무한)으로 기다려 예외가 나지 않고, 26절의 재시도·중단이 시작되지 않는다. 사용자가 두 앱 모두에 넣기로 했고 값은 `connectTimeout=3000`·`socketTimeout=10000`으로 정했다([ADR 0052](../adr/0052-set-mysql-driver-timeouts-per-app.md), ADR 0049에는 후속 링크만 추가). 공통 `jpa.yml`이 아니라 두 앱의 `application.yml`에 `datasource.mysql-jpa.main.data-source-properties`로 둔다.

테스트를 먼저 썼다.

| 테스트 | 변경 전 | 변경 후 |
| --- | --- | --- |
| 두 앱 `DatabaseDriverTimeoutTest` 설정 검사: 실제 `HikariDataSource`가 드라이버에 두 속성과 기존 `rewriteBatchedStatements`를 넘김 | 실패 | 통과 |
| streamer 풀 커넥션의 `SELECT SLEEP(15)` | 예외 없이 끝까지 기다림 | — |
| streamer 풀 커넥션의 `SELECT SLEEP(30)` (`experiment` tag) | — | 20.5초에 `SocketTimeoutException` 원인 예외 |

- 처음 만든 "연결은 받고 응답하지 않는 서버" 테스트는 Hikari가 전역 `DriverManager` login timeout(3초)을 바꿔 둔 탓에 설정과 무관하게 3초에 끝나 버렸다. 실제 문제(풀 커넥션의 쿼리)를 재는 `SLEEP` 방식으로 바꿨다.
- 예외까지 걸린 시간은 socketTimeout의 약 두 배였다. 드라이버가 10초에 읽기를 포기한 뒤 연결 정리에서 한 번 더 최대 10초를 기다린다. `SLEEP(15)`에서는 그 두 번째 읽기에 서버 응답이 와서 15초에 끝났다.
- API 테스트와 같은 명령으로 돌린 한 실행에서는 `SLEEP(30)`이 예외 없이 끝났다(약 151초). 원인을 밝히지 못해 이 시간 측정은 `experiment` tag로 평소 `check`에서 뺐다. 설정 검사는 `check`에 남는다.

첫 전체 회귀에서 week5의 `PaymentChaosTest.dbDownAfterPgAnswers...`가 실패했다(15초 미만 기대, 20.06초). 이 테스트는 JDBC URL에 `socketTimeout=3000`을 붙여 DB 정지를 빨리 드러내는데, 새 `data-source-properties` 값이 URL 값보다 우선해 10초 × 2가 됐다. 테스트가 같은 키(`data-source-properties.socketTimeout=3000`, `connectTimeout=2000`)로 3초를 넣게 바꿨고 15초 기대값은 그대로다. 이 클래스 4건이 다시 통과했다.

| 검사 (한 명령, `--continue`) | 결과 |
| --- | --- |
| `commerce-api:check` | 524건 통과, 실패·오류·skip 0 |
| `commerce-streamer:check` | 88건 통과, 실패·오류·skip 0 |
| `modules:kafka:check` | 7건 통과 |
| Checkstyle 전체 | 위반 0 |
| 합친 명령 | BUILD SUCCESSFUL, 10분 32초 |

실제 DB 서버를 멈추거나 네트워크를 끊어 재현하지 않았다. 잠금 경합이 몰릴 때의 대기 분포도 재지 않았다.

<a id="max-block-measurement"></a>

## 32. max.block.ms 측정: metadata가 있을 때와 없을 때

ADR 0051의 5초가 실제로 무엇을 묶는지 측정했다. `ProducerMaxBlockExperimentTest`(`experiment` tag)는 실제 MySQL·PG fixture·Kafka 1대로 주문을 확정하고, `reconcile` 시간과 시작 1초 뒤부터의 Hikari 사용 중 커넥션 최댓값을 잰다. 브로커를 끈 뒤 두 경우를 본다.

| 경우 | reconcile 시간 | 1초 뒤 사용 중 커넥션 최댓값 | 남은 로그 |
| --- | --- | --- | --- |
| 정상(브로커 켜짐, 준비용) | 212ms | — | 성공 로그 |
| 1. metadata 있음(이미 보낸 producer), 브로커 꺼짐 | 50ms | 0 | 즉시 실패 로그 없음. 2단계 실패는 delivery.timeout(120초) 뒤에 나므로 기다리지 않았다 |
| 2. metadata 없음(producer 새로 생성), 브로커 꺼짐 | 5,048ms | 1 | (가) `Order event publication failed after commit` |

- 브로커가 꺼져도 **이미 metadata를 기억하는 producer는 요청을 막지 않는다.** 요청이 5초 묶이는 건 앱이 막 떴거나 producer가 새로 만들어진 뒤 metadata가 없을 때다.
- 경우 2에서 요청이 끝나기 전 1초 이후에도 사용 중 커넥션이 1개였다. AFTER_COMMIT이 커넥션 반납 전에 실행되어, 5초 동안 **DB 커넥션도 함께 쥔다**는 설계 문장을 실행으로 확인했다. 경우 2의 주문은 CONFIRMED로 남았다.
- 테스트 profile의 풀 크기는 10이다. 운영 기본(40)에서 동시 요청이 몰릴 때의 고갈 시점은 재지 않았다.

실험 1건과 API test Checkstyle 통과, BUILD SUCCESSFUL 1분 12초. 실행 뒤 broker·PG·DB 자원은 정리했다.

<a id="min-isr-loss-experiment"></a>

## 33. acks=all에서 min.insync.replicas 1과 2의 유실 비교

`order-events`에 `min.insync.replicas=2`를 고른 근거를 실제 유실 장면으로 확인했다. `KafkaMinIsrLossExperimentTest`(`experiment` tag)는 데이터 broker 3개·별도 controller 1개 fixture에서 replica 2개(broker 1=리더, 2=follower)인 topic을 만들고, `acks=all`·idempotent producer로 같은 장면을 `min.insync.replicas` 1과 2에서 반복한다.

1. 둘 다 따라와 있을 때 `before` 1건 → follower 정지 → ISR이 리더 하나로 줄 때까지 대기(`replica.lag.time.max.ms`=5000)
2. 리더 혼자일 때 20건 전송
3. 리더 정지, 뒤처진 follower 재시작, 10초 대기 → Admin API로 unclean 선출 요청
4. 옛 리더 재시작 → ISR 2개 복귀 뒤 1건 전송 → 전체 읽기

| 관찰 | min.insync=1 | min.insync=2 |
| --- | --- | --- |
| follower 정지 뒤 ISR | [1] | [1] |
| 리더 혼자일 때 20건 | **20건 성공 응답**, 리더 end offset 21 | **20건 실패**(원인 `TimeoutException`), end offset 1 |
| 리더 정지 뒤 10초 동안 뒤처진 follower의 자동 리더 선출 | 없음 | 없음 |
| unclean 선출 뒤 남은 record | `before` | `before` |
| 옛 리더 복귀 뒤 전체 record | `before`, `after-recovery` | `before`, `after-recovery` |
| 성공 응답을 받고 사라진 record | **20건** | 0건 |
| 복구 뒤 처음 쓴 record의 offset | 1 (사라진 record가 쓰던 offset을 재사용) | 1 |

- `acks=all`의 성공은 그 순간 ISR 전부의 저장이다. ISR이 리더 하나로 줄면 `min.insync=1`은 리더 혼자의 저장으로 성공을 돌려주고, 그 리더를 잃으면 확인받은 record도 잃는다. `min.insync=2`는 같은 순간 쓰기를 받지 않아 실패가 producer에 드러났다.
- `min.insync=2`의 실패 원인은 `NotEnoughReplicas`가 아니라 `TimeoutException`으로 보였다. 재시도 가능한 거절을 기본 retries로 계속 다시 보내다가 delivery timeout(이 실험 5초)이 먼저 끝났다.
- 옛 리더는 돌아오면서 새 리더의 로그에 맞춰 자기 로그를 잘랐다. 사라진 record의 offset 1에 다른 record가 들어갔다. offset만으로는 "같은 record"를 알 수 없다.
- 뒤처진 follower가 10초 동안 저절로 리더가 되지 않은 것이 broker 기본값(unclean 선출 끔) 때문인지 이 버전(3.5.1) KRaft의 동작 때문인지는 따로 확인하지 않았다. 유실 장면은 Admin API로 unclean 선출을 직접 요청해 만들었다.

실험 1건(두 장면)과 test Checkstyle 통과, BUILD SUCCESSFUL 2분 42초. 처음 실행에서는 `min.insync=2` 장면의 복구 뒤 쓰기를 옛 리더 복귀 전에 해 ISR 1로 거절당했다(Kafka의 정상 동작). 쓰기를 ISR 2개 복귀 뒤로 옮겨 다시 실행했다. 장애는 컨테이너 정지로 만든 통제된 조건이며 `acks=0`·`acks=1`은 비교하지 않았다.

<a id="long-consumer-retry"></a>

## 34. 메시지 문제가 아닌 소비 실패를 몇 분 동안 재시도한 뒤에만 멈춤

1초·2초 두 번 재시도 뒤 멈추는 정책(ADR 0042·0043)은 DB failover 같은 일시 장애에도 소비를 멈추고, 그때마다 사람이 재시작해야 했다. 사용자가 "실패 record를 건너뛰지 않는다"는 원칙은 두고, 멈추기 전에 충분히 기다리는 쪽(1초부터 2배, 한 간격 최대 30초, 간격의 합 5분)을 골랐다. [ADR 0053](../adr/0053-retry-transient-consumer-failures-for-minutes-before-stopping.md)을 추가하고 ADR 0042·0043을 Superseded로, ADR 0041·0049에는 후속 링크만 표시했다.

### 구현

- `OrderConfirmedKafkaErrorHandling`: `ExponentialBackOffWithMaxRetries(2)`를 `ExponentialBackOff`(첫 간격·배수·최대 간격·간격의 합)로 바꿨다. 값은 `OrderConfirmedRetryProperties`(`commerce.order-events.consumer.retry.*`, 기본 1s·2·30s·5m)로 받는다. 처음에 `@Value`로 `Duration`을 받았더니, Boot의 변환 서비스가 없는 설정 테스트 context에서 `"1s"`를 변환하지 못해 실패했다. `@ConfigurationProperties` record로 바꿨다.
- 처음에는 대기 동안 consumer가 poll하지 못해 group에서 빠질까 봐 `ContainerPausingBackOffHandler`(container pause + 별도 scheduler)를 넣었다. 다시 확인하니 기본 처리기는 **한 간격씩** 잠든 뒤 다시 poll하므로, 한 간격(최대 30초)만 `max.poll.interval.ms`(2분)보다 짧으면 된다. 같은 테스트가 pause 없이도 통과해 pause와 scheduler를 뺐다.

### 검증

`OrderConfirmedKafkaErrorHandlingIntegrationTest`를 바꿨다. 짧은 값(0.5초부터 2배, 한 간격 최대 2초, 합 10초)과 `max.poll.interval.ms` 4초를 넣고, 실제 `@KafkaListener`에 rebalance listener를 붙여 revoke를 센다.

| 장면 | 관찰 |
| --- | --- |
| 1. 메시지 오류·충돌 | 그대로: DLQ에 원본 offset 1·2, 42 수량 유지, offset 4 |
| 2. DB가 6초 동안 실패했다가 회복 (max.poll 4초보다 김) | 4번 이상 실패, 간격 모두 2.8초 미만, 처음부터 마지막 실패까지 4초 초과. **listener 멈추지 않음**, revoke 0회, 44·45 저장, offset 6 |
| 3. 계속 실패 | 처음부터 마지막 실패까지 9초 이상 재시도 뒤 멈춤. 그동안 revoke 0회, offset 6 유지. 원인 제거·재시작 뒤 46·47 저장, offset 8 |
| 4. DLQ 전송 실패 | 그대로: 멈춤·offset 8 유지 → 복구 뒤 DLQ에 원본 offset 8, offset 10 |

관련 4클래스(이 통합 테스트, 준비 설정 2, 주문 설정 3, ArchUnit 1)와 streamer Checkstyle main/test 통과. pause를 뺀 뒤 다시 실행해도 통과했다. 이어서 `commerce-streamer:check` 전체 88건 통과(실패·오류·skip 0, Checkstyle 위반 0, BUILD SUCCESSFUL 5분 31초). commerce-api와 Kafka 모듈은 이 변경에 영향이 없어 다시 돌리지 않았다. 운영 값(5분)으로 직접 돌리지 않았고 실제 DB 서버 중단은 재현하지 않았다. 재시도 중 rebalance가 겹칠 때 재시도 추적이 새로 시작되는지는 확인하지 않았다.

<a id="batch-ack-comparison"></a>

## 35. BATCH / MANUAL_IMMEDIATE의 재전달 범위와 DB 효과 비교

사용자는 강의 로그 파이프라인 개선 글을 참고해, 멱등한 동기 DB 처리에서 BATCH와 MANUAL_IMMEDIATE를 비교하도록 했다. 현재 업무용 MI 설정·listener·오류 처리기·DB 정책은 유지하고 `KafkaBatchAckExperimentTest`만 추가했다. 기존 20절은 MANUAL과 MI의 비교이며 이번은 BATCH와 MI의 비교다.

실제 Kafka 3.5.1·MySQL 8.0 Testcontainers, 고유 topic/group·partition 1·RF1·consumer 1개를 사용했다. 주문 42·43·44 세 건을 먼저 발행하고 `max.poll.records=3`, 첫 listener의 실제 position=3을 확인했다. 두 후보 모두 기존 main 어댑터·DB handler를 사용한다. BATCH는 handle(record), MI는 handle(record, acknowledgment)로 DB 처리 뒤 ack한다. record listener를 유지하고 주문마다 하나의 DB TX를 사용한다.

첫 주문 DB commit 뒤, MI는 ack도 마친 다음, listener 정상 반환 전 같은 hook 예외와 CommonContainerStoppingErrorHandler로 중단했다. 새 container가 같은 group으로 seek·offset 재설정·추가 발행 없이 이어 읽는다.

| 관찰 | BATCH | MANUAL_IMMEDIATE |
| --- | --- | --- |
| 첫 poll position | 3 | 3 |
| 첫 주문 DB commit 뒤·중단 뒤 committed offset | 0·0 | 1·1 |
| 중단 시 DB | 완료 기록 1·활동 2 | 완료 기록 1·활동 2 |
| 재시작 후 listener에 전달된 offset | 0·1·2 | 1·2 |
| 최종 committed offset | 3 | 3 |
| 최종 DB | 완료 기록 3·활동 6 | 완료 기록 3·활동 6 |
| 저장 포트 호출 합계 | 완료 3·활동 6 | 완료 3·활동 6 |
| 기존 주문 42의 행·수량·시각·완료 snapshot | 유지 | 유지 |
| Kafka 끝 offset | 3 | 3 |

BATCH는 이미 저장된 주문 42를 다시 받지만 기존 동일 사실 no-op으로 추가 저장하지 않는다. 이번 조건에서 MI는 재전달 범위를 줄였고, DB 중복 효과를 막은 것은 기존 멱등 처리다. commit 호출 수·처리량·지연을 측정하지 않았으며 업무용 BATCH와 retry/DLQ를 결합한 검증도 아니다. 첫 listener 반환을 막는 hook이므로 OS 강제 종료로 표현하지 않는다. 조건·가로 흐름·선택 대기는 [실험 11절](kafka-settings-experiments.md#batch-ack-experiment)에 정리했고 blog-draft에도 같은 결과와 한계를 반영했다.

### 실행 검증

```bash
env DOCKER_HOST=unix:///Users/johyeon-a/.docker/run/docker.sock \
  JAVA_TOOL_OPTIONS=-Dapi.version=1.44 \
  JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home \
  PATH=/opt/homebrew/opt/openjdk@21/bin:$PATH \
  ./gradlew :apps:commerce-streamer:test \
    --tests '*KafkaBatchAckExperimentTest' \
    --tests '*KafkaManualAckExperimentTest' \
    --tests '*LayeredArchitectureTest' \
    :apps:commerce-streamer:checkstyleMain \
    :apps:commerce-streamer:checkstyleTest --offline
```

| 검사 | 최종 실행 결과 |
| --- | --- |
| KafkaBatchAckExperimentTest | 새 비교 2건 통과 |
| KafkaManualAckExperimentTest | 기존 ack 경계·DB 실패/복구·재전달 5건 통과 |
| LayeredArchitectureTest | ArchUnit 1건 통과 |
| 합계 | 8건, 실패·오류·skip 0 |
| Checkstyle | main UP-TO-DATE·test 실행 통과, 보고서 위반 0 |
| Gradle | BUILD SUCCESSFUL·1분 27초 |

초기 실행도 8건·Checkstyle 통과(1분 31초)였다. 정적 검토에서 MI의 commit이 관찰 callback보다 먼저 보일 수 있는 경쟁을 찾아, 재시작 완료를 기다릴 때 최종 offset과 수신 목록을 함께 검사하도록 보강했다. 위 값은 그 최종 코드로 다시 실행한 결과다. 이전 전체 회귀 API 524·streamer 88·Kafka 모듈 7건은 이번에 재실행하지 않았다.

commerce-change-review 기준으로 실제 poll의 크기, 정상 DB TX 완료·중단 hook·미커밋 재전달·같은 group 재시작·행 불변·저장 호출 수와 현재 주차 문서를 대조했다. 이번 비교 범위에서 추가 수정이 필요한 계약 위반은 발견하지 않았다. 업무용 값 변경을 채택하지 않았으므로 기존 ADR 본문·상태는 변경하지 않았다.

<a id="dlt-timeout-verification"></a>

## 36. DLT 지연 실패의 실제 대기·원본 위치·복구 검증

사용자는 즉시 거절되는 DLT 크기 초과와 달리, 재시도 가능한 거절로 늦게 실패하는 경우를 확인하도록 했다. OrderConfirmedDlqTimeoutExperimentTest를 experiment 태그로 추가했다. 업무용 설정은 바꾸지 않고 실제 @KafkaListener·오류 처리기·DB handler에 격리 Kafka·MySQL을 연결했다.

원본 topic은 정상이며 DLT만 RF1·최소 ISR2로 만들어 writes를 NotEnoughReplicas로 거절시켰다. 형식 오류 offset 0과 정상 주문 43(offset 1)을 미리 발행했다. 업무용 기본 delivery timeout 120초·최소 결과 대기 10초를 유지했다. 실제 factory 설정과 KafkaConfig를 확인해 주문용 poll 제한 300초, recoverer의 결과 대기 예산 125초도 검증했다.

- 수신 후 12초에도 listener는 실행 중이고 producer record-retry-total은 13이었다. 원본 offset 0·DB 0·DLT 0을 유지했고 뒤 주문은 처리하지 않았다.
- 첫 수신부터 120,118ms 뒤 listener가 중단됐다. 재시도 지표는 121, 원본 offset 0·DB 0·DLT 0이었다.
- DLT 최소 ISR을 1로 돌리고 producer 재생성 후 같은 group으로 시작했다. 전체 listener 수신 offset 0·0·1, 최종 committed offset 2·DLT 1·완료 기록 1·활동 2를 확인했다. DLT의 원문 key·bytes·원본 topic·offset·group도 대조했다.

복구의 producer 재생성은 factory.reset()으로 앱 재시작 일부를 모사한 것이다. container.start만으로 같은 만료 producer가 회복한다고 주장하지 않는다. broker 무응답·네트워크 분할·OS 종료·여러 consumer의 중단 범위도 이번 실행으로 증명하지 않는다. 상세 조건·선택 대기는 [실험 12절](kafka-settings-experiments.md#dlt-timeout-experiment)에 둔다.

### 실행 결과

```bash
env DOCKER_HOST=unix:///Users/johyeon-a/.docker/run/docker.sock \
  JAVA_TOOL_OPTIONS=-Dapi.version=1.44 \
  JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home \
  PATH=/opt/homebrew/opt/openjdk@21/bin:$PATH \
  ./gradlew :apps:commerce-streamer:experimentTest \
    --tests '*OrderConfirmedDlqTimeoutExperimentTest' \
    :apps:commerce-streamer:test --tests '*LayeredArchitectureTest' \
    :apps:commerce-streamer:checkstyleMain :apps:commerce-streamer:checkstyleTest --offline
```

새 experiment 1건·ArchUnit 1건, 실패·오류·skip 0. main Checkstyle UP-TO-DATE·test Checkstyle 실행 통과, 위반 0, BUILD SUCCESSFUL·2분 45초였다. 앞선 BATCH 비교의 8건과 전체 회귀를 이번에 다시 돌린 것은 아니다.

기존 코드의 “10초 상한”과 “주문용 poll 제한 2분” 주석을 현재 실제 동작에 맞게 정정했고, 현재 설계도 함께 고쳤다. 행동·YAML 값은 변경하지 않았다. 기존 ADR 0049·0053은 결정 본문을 보존하고 상단에 검증 결과와 현재 설명 링크만 추가했다. 시간 기준을 새로 채택한 것이 아니므로 새 ADR은 만들지 않았다.

commerce-change-review 기준으로 즉시 거절과 producer 재시도 소진의 차이, 완료 전 원본 위치 보호, 중단 뒤 같은 group 재수신, producer 재생성의 복구 범위와 현재 주차 문서를 대조했다. 실제 오류가 정해진 값보다 오래 걸린 사실은 기록하되, 10초 상한을 새로 구현한 것으로 표현하지 않았다.

주석 정정 뒤에는 ArchUnit 1건·main/test Checkstyle을 다시 실행해 모두 통과했다(BUILD SUCCESSFUL·13초). 행동이 달라지지 않은 주석 정정이므로 약 2분이 필요한 native timeout 실험을 반복하지 않았다.

<a id="batch-adoption"></a>

## 37. 주문용 BATCH 채택과 실제 listener·오류 처리기 검증

사용자가 BATCH/MI 비교와 재전달 비용을 검토한 뒤 BATCH로 변경하기로 했다. [ADR 0054](../adr/0054-commit-order-consumer-offsets-per-poll.md)를 추가하고 ADR 0046은 Superseded, 오류 처리 정책이 유효한 ADR 0049는 Accepted로 유지하면서 상단에 후속 링크를 표시했다. 관련 기존 ADR의 결정 본문은 SHA-256 비교로 보존을 확인했다. ADR 0040·0041·0044·0045의 상단 후속 링크도 현재 BATCH 결정으로 연결하고 본문은 보존했다. 작업 날짜는 ADR에 추가하지 않았다.

### 변경과 책임

- 주문용 factory의 ack mode만 BATCH로 바꿨다. record listener·주문별 동기 DB TX·자동 commit 끔·동기 commit·배정 commit 끔·earliest·concurrency 계약은 유지한다.
- 실제 listener는 Acknowledgment를 받지 않고 어댑터의 handle(record)를 호출한다. DB commit 또는 검증된 동일 사실 no-op 뒤 정상 반환하며, 위치는 container가 poll 처리 경계에서 기록한다. 예외를 잡아 성공으로 반환하지 않는다.
- 실제 DefaultErrorHandler를 ackAfterHandle=true·commitRecovered=false로 연결했다. DLT 성공 뒤 정상 반환한 record의 위치는 container가 관리하고, DLT 실패·DB 재시도 소진은 기존 중단 handler가 예외로 끝내 실패 record를 지나지 않는다.
- 수신 어댑터의 두 인자 메서드는 과거 MANUAL/MI 비교 실험용으로 남겼고 업무용 listener는 사용하지 않는다. 기존 비교 테스트·공유 demo용 MANUAL factory는 유지했다.
- broker·producer·DB 모델·재시도 시간·DLT 결과 대기 예산은 바꾸지 않았다. BATCH는 묶음 listener·묶음 DB insert로 바꾸는 결정이 아니다.

### 기대값부터 변경해 확인한 red

OrderConfirmedKafkaConfigurationTest의 주문용 기대값을 BATCH로 먼저 바꾼 뒤 실행했다. 설정 3건과 자동 포함 ArchUnit 1건 중 해당 설정 1건만 실패했다(expected BATCH, actual MANUAL_IMMEDIATE, BUILD FAILED·10초). 이후 구현을 변경했다. 기대값이나 검사 규칙을 완화하지 않았다.

### 실제 listener의 정상·중복·미커밋 재전달

기존 OrderConfirmedKafkaListenerIntegrationTest를 확장했다. Kafka 3.5.1·MySQL 8.0·partition 1·RF1·consumer 1개, 실제 factory·@KafkaListener·parser·DB handler를 사용한다. 정상 42(offset 0)·동일 사실 재발행(offset 1)을 반영한 뒤 43(offset 2)의 DB commit 직후 listener 반환 전에서 대기했다. DB TX 비활성과 위치 2·완료 2·활동 4를 확인하고 통제된 예외로 중단했다.

같은 group으로 다시 시작하자 offset 2가 재전달됐다. 완료/활동 행 ID·수량은 같았고 위치만 3으로 진행했다. 보고 값은 committedOffsets=[0,1,2,2,2,3]·replayedOffsets=[2,2]·완료 2·활동 4다. 중단 hook은 테스트 전용 stopping handler를 사용하므로 업무용 오류 분류 검증과 구분한다.

### 실제 업무용 오류 처리기의 실패·복구

OrderConfirmedKafkaErrorHandlingIntegrationTest는 현재 BATCH factory·listener와 실제 업무용 오류 처리기를 사용해 기존 기대값을 유지한 채 통과했다. 재시도 관찰 값은 테스트용 0.5초부터 최대 2초·누적 대기 10초이며 운영의 누적 대기 5분을 그대로 실행한 것은 아니다.

| 장면 | 확인한 결과 |
| --- | --- |
| 정상 42·수량 충돌·깨진 JSON·정상 43 | 충돌/JSON만 DLT(원본 offset 1·2), DB는 정상 두 주문, 위치 4 |
| 44의 저장 호출이 6초 실패·정상 45 | 수동 재시작 없이 둘 다 반영·위치 6·중간 revoke 0 |
| 46이 계속 실패·정상 47 | 중단 시 위치 6·46/47 미반영. 원인 제거·같은 group 재시작 뒤 위치 8·둘 다 반영 |
| DLT가 offset 8을 크기 제한으로 거절·정상 48 | 중단 시 위치 8·48 미반영. 제한 복구·재시작 뒤 DLT 보존·48 반영·위치 10 |

DB 장애는 저장 호출에서 예외를 만드는 방식이다. DB 서버 종료·운영 다중 consumer 장애나 OS 강제 종료와 구분한다.

### BATCH에서도 native DLT 지연 실패 확인

OrderConfirmedDlqTimeoutExperimentTest를 현재 BATCH로 다시 실행했다. source는 정상이고 DLT만 최소 ISR 2·실제 ISR 1로 만들어 producer가 재시도하게 했다. 기본 delivery timeout 120초·recoverer 결과 대기 예산 125초·주문용 poll 제한 300초는 유지했다.

| 관찰 | 결과 |
| --- | --- |
| 12초 시점 | producer 재시도 13회·listener 실행 중·원본 위치 0·DB 0·DLT 0 |
| 중단 시점 | 첫 수신 뒤 120,159ms·producer 재시도 120회·원본 위치 0·DB 0·DLT 0 |
| 복구 | DLT 최소 ISR 복구·producer 재생성·같은 group 재시작 |
| 전체 수신/최종 | offset 0·0·1, 원본 위치 2·DLT 1·완료 1·활동 2, 원문/출처 일치 |

36절의 이전 MI 값(120,118ms)은 당시 기록으로 보존했다. producer factory reset은 앱 재시작 중 producer 재생성을 모사하며 container.start만의 복구를 증명하지 않는다. 모든 timeout이 DLT 미저장을 뜻하거나 재전송이 정확히 한 번이라는 보장도 아니다. 이 실행에서는 시간 기준 선택을 대기로 남겼고, 이후 기본 대기를 받아들인 결정은 38절과 ADR 0055에 기록했다.

### 최종 실행과 문서 대조

```bash
env DOCKER_HOST=unix:///Users/johyeon-a/.docker/run/docker.sock \
  JAVA_TOOL_OPTIONS=-Dapi.version=1.44 \
  JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home \
  PATH=/opt/homebrew/opt/openjdk@21/bin:$PATH \
  ./gradlew :apps:commerce-streamer:check \
    :apps:commerce-streamer:experimentTest --tests '*OrderConfirmedDlqTimeoutExperimentTest' \
    --offline --no-parallel
```

- streamer 정규 전체 90건(ArchUnit 포함)·별도 지연 DLT experiment 1건, 실패·오류·skip 모두 0.
- main/test Checkstyle 모두 실행·위반 0. BUILD SUCCESSFUL·8분 35초.
- API·Kafka 모듈 전체 검사와 두 앱 W6 외부 클러스터 사례는 이번 변경에서 재실행하지 않았다. 해당 검사는 과거 절의 당시 결과로 보존한다.

commerce-change-review 기준으로 정상 반환 전 DB 완료, 동일 사실 no-op, BATCH와 DLT 복구 commit 연결, 재시도/중단 시 실패 offset 보호와 같은 group 복구를 호출 경로·실제 결과와 대조했다. 변경 범위에서 추가 결함은 발견하지 않았다. 여러 consumer에서 listener 전체 중단·rebalance 경쟁·운영 5분 값 그대로의 실행·처리량은 미검증이다. framework 기본 비재시도 예외 분류는 변경하지 않았으므로 모든 예외가 동일 기간 재시도된다고 일반화하지 않는다.

현재 설계·실험 상태 표·blog-draft의 선택과 멘토 질문을 BATCH에 맞췄다. study·설정 선택 노트의 현재 ack 표기도 갱신하고 기존 계획·학습 결과에는 당시 조건과 현재 결정의 연결 설명을 남겼다. 과거 비교 결과와 채택 ADR 본문은 보존했다. 블로그에는 구현 설명 대신 “다시 읽어도 구매가 늘지 않으므로 직접 완료 표시 대신 묶음 위치 기록을 선택했다”는 사고 흐름을 남겼고, 성능 우위를 측정했다고 적지 않았다.

<a id="dlt-wait-decision"></a>

## 38. DLT 전송 대기를 기본값으로 두기로 결정

36절의 지연 실패 결과를 놓고, DLT producer의 delivery timeout을 줄일지 검토했다. 사용자는 기본 120초를 받아들이기로 했다. 재시도 가능한 DLT 실패는 메시지 문제와 DLT 장애가 겹치는 드문 경우이고, 멈추면 사람이 재시작해야 하는 비용이 최대 2분 지연보다 크며, 어느 쪽이든 원본은 지킨다는 이유다. [ADR 0055](../adr/0055-accept-default-delivery-timeout-for-dead-letter-publishing.md)을 추가했다.

동작은 바뀌지 않는다. 코드는 주석의 참조만 고쳤고 새 테스트를 실행하지 않았다. ADR 0051은 본문을 보존하고 상단에 "10초" 문장의 정정을 표시했다. design.md와 실험 12절의 선택 대기 표기를 결정으로 맞췄다.

<a id="volume5-base-integration"></a>

## 39. 머지된 5주차 기준을 volume-6에 통합

원본 저장소의 `hyeon-aa`에 [5주차 PR #76](https://github.com/loopers-labs/loop-pack-be-l2-vol5-java/pull/76)이 병합된 `086f761`을 `volume-6`에 반영했다. 기존 6주차에는 빠져 있던 5주차 후속 커밋 `1dc9309`의 PG 조회 연결 부족 분류·호출/풀 획득 계측·관련 검증을 포함한다. [6주차 PR #106](https://github.com/loopers-labs/loop-pack-be-l2-vol5-java/pull/106)은 기존처럼 원본 `hyeon-aa`를 기준으로 유지하며 PR 자체는 병합하지 않는다.

### 통합 범위와 정적 대조

- 38개 파일을 충돌 없이 병합했다. `apps/commerce-api/build.gradle.kts`는 자동 병합됐으며 6주차 Kafka 의존성·공유 test fixture와 5주차의 실험 전용 작업이 함께 남는 것을 확인했다.
- PG 조회 후속 결정은 기존 ADR 0060~0062를 따른다. 새 Kafka 설정·재시도 정책·트랜잭션 경계를 채택한 변경은 아니다. 주문 최초 SUCCESS의 이벤트 준비와 `AFTER_COMMIT` 발행, streamer의 DB 저장 뒤 BATCH 위치 기록은 유지한다.
- 병합 diff의 공백 검사(`git diff --check HEAD^1 HEAD`)는 통과했다. 기존 미추적 파일과 개인 학습·블로그 자료는 변경 범위에 넣지 않는다.

### 실행 검증

첫 `:apps:commerce-api:check :apps:commerce-streamer:check`는 Testcontainers의 Docker 연결이 HTTP 400으로 거절돼 API 통합 테스트의 컨텍스트 생성 단계에서 실패했다. Docker Desktop의 기존 API 호환 설정을 이번 실행 프로세스에만 적용해 같은 검사를 다시 실행했다. 이 환경 실패를 기능 회귀로 판정하지 않는다.

```bash
env DOCKER_HOST=unix:///Users/johyeon-a/.docker/run/docker.sock \
  JAVA_TOOL_OPTIONS=-Dapi.version=1.44 \
  JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home \
  ./gradlew :apps:commerce-api:check :apps:commerce-streamer:check --offline --no-parallel
```

호환 설정을 적용한 재실행은 `BUILD SUCCESSFUL`·5분 25초였다.

- commerce-api 정규 테스트 550건(ArchUnit 1건 포함)을 실제 실행했고 실패·오류·skip은 모두 0이다. API main/test Checkstyle은 첫 병합 검사에서 통과했으며 재실행에서는 UP-TO-DATE였다.
- commerce-streamer의 `check`도 통과했다. main Checkstyle은 실행·위반 0, test Checkstyle과 정규 test는 UP-TO-DATE였다. 기존 XML의 90건(ArchUnit 포함)·실패/오류/skip 0은 이전 실행 결과이며, 이번 병합에서 streamer 테스트 90건을 새로 실행했다고 기록하지 않는다.
- 두 앱의 `check`는 33개 작업 중 3개 실행·30개 UP-TO-DATE로 끝났다. 테스트 기대값·Checkstyle·ArchUnit 규칙이나 Kafka 설정은 바꾸지 않았다.
- experiment 태그의 부하·장애·설정 비교 실험과 로컬 3대 클러스터의 두 앱 사례는 이번 기준 병합에서 실행하지 않았다.

## 아직 검증하지 않은 것

- 운영 재시도 값(간격의 합 5분)을 그대로 쓴 실행, 실제 DB 서버 중단·failover
- concurrency 3에서 listener 전체가 함께 멈추는 동작, 재시도 중 rebalance가 겹칠 때의 재시도 횟수
- 프로세스 강제 종료(`kill -9`)와 `session.timeout.ms` 동안의 partition 정체, 처리 시간이 `max.poll.interval.ms`를 넘길 때의 재전달
- broker 중단에 의한 producer 2단계(비동기) 전송 실패, `acks=0`·`acks=1`의 유실 비교
