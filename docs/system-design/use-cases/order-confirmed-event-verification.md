# 주문 확정 이벤트 검증

> [주문 확정 이벤트](order-confirmed-event.md)의 [검증 기준](order-confirmed-event.md#검증-기준)을 확인하는 테스트 위치와 실험 설계

결과는 실제 broker와 MySQL에서 확인한다. 기대값은 설계 문서의 검증 기준을 따르고, 이 문서는 어떤 테스트가 그것을 어떻게 확인하는지 정리한다.

구현 정책은 [Producer](../../../apps/commerce-api/docs/order-confirmed-producer.md), [Broker](../../../modules/kafka/docs/broker.md), [Consumer](../../../apps/commerce-streamer/docs/purchase-activity-consumer.md)의 모듈 문서를 따른다.

## 검증 위치

commerce-api는 확정 시각 저장과 이벤트 구성·발행을, commerce-streamer는 계약 해석과 구매 활동 소비를 각자의 테스트로 검증한다. 두 앱을 한 테스트에 묶지 않는다. 이벤트 계약은 [JSON 예시](order-confirmed-event.md#이벤트-계약)를 기준으로 producer는 생성 결과를, consumer는 해석 결과를 검증하고, 확정부터 구매 활동까지의 전체 연결은 실제 앱을 함께 띄운 로컬 실행으로 확인한다.

| commerce-api 테스트 | 검증 |
| --- | --- |
| `OrderConfirmedEventTest` | 주문 ID로 유도한 eventId, 저장된 확정 시각, 상품 ID 오름차순 품목으로 이벤트 구성. 미확정 주문과 확정 시각이 없는 이전 확정 주문(POINT·PG)은 거절해 소급 발행하지 않음 |
| `OrderConfirmedMessageV1Test` | 계약 필드만 담은 V1 JSON과 orderId 문자열 key. 다른 시간대의 확정 시각을 마이크로초까지 UTC로 표현 |
| `PaymentConfirmedAtIntegrationTest` | 최초 PG 성공에 주입한 Clock 시각을 마이크로초로 잘라 저장. 중복 성공에도 처음 시각 유지, 실패 종결에는 확정 시각 없음 |
| `OrderConfirmedAtMigrationIntegrationTest` | 컬럼 추가 뒤 기존 주문의 확정 시각은 비워 두고 상태·결제 값·수정 시각은 바꾸지 않음 |
| `OrderConfirmedPublicationTest` | 최초 성공 commit 뒤 한 번 통지. 같은 성공 재반영, 처리 중·실패·`UNKNOWN`, commit 실패, 동시 반영에서 추가 통지 없음 |
| `OrderConfirmedKafkaPublisherTest` | send 호출 예외, 이미 실패한 future, listener 반환 뒤 실패한 future를 각각 확인. `eventId`·`orderId`·원인을 error 로그로 남기고 전파하지 않음 |
| `OrderConfirmedKafkaDeliveryIntegrationTest` | 실제 주문 API·테스트 PG·reconcile·Kafka로 [발행 누락 사례](#발행-누락-사례). producer 설정과 로컬·테스트 topic 초기화 |

| commerce-streamer 테스트 | 검증 |
| --- | --- |
| `OrderConfirmationTest` | 양수 규칙, 동일 상품 수량 합산·정렬, overflow 거절과 불변 목록 |
| `OrderConfirmedParserTest` | JSON 속성·품목 순서에 무관한 비교, key 관계, 버전·필드 타입·필수 값 검증. 중복 JSON 필드와 뒤에 붙은 추가 JSON도 거절 |
| `OrderConfirmedVersionDispatchTest` | 여러 해석기의 버전별 선택·기존 버전 유지, 미지원 버전과 중복 등록 거절. listener 메서드를 직접 호출해 미지원 버전의 handler·ack 미호출을 확인하는 단위 테스트. 실제 컨테이너 중단·broker offset의 통합 검증과 구분. 추가 버전 선택은 테스트 대역으로 검증하며 실제 V2 명세를 정의한 것은 아님 |
| `PurchaseActivityIntegrationTest` | 실제 MySQL의 행·수량·마이크로초 확정 시각, ID·payload 충돌, 중간 품목 오류의 전체 rollback, 각 유일 제약과 동시 경쟁 뒤 새 TX 판정. 저장 뒤 flush·clear한 새 조회와 별도 SQL로 확인 |
| `PurchaseActivityRepositoryTest` | DB 오류 코드에 의존하지 않고 완료 기록의 알려진 유일 제약만 중복으로 변환. 한정자·대소문자 처리, 미상·다른 제약과 비유일성 오류의 원래 예외 유지 |
| `KafkaClientConfigTest` | consumer의 byte 배열 값과 auto commit off, producer의 `acks=all`·멱등성, producer·consumer·admin의 같은 broker 주소와 fixture broker 왕복 |
| `PurchaseActivityKafkaIntegrationTest` | 실제 broker의 JSON record를 실제 Spring listener로 저장한 뒤 group committed offset 확인. 같은 사실을 새 record로 다시 보내도 완료 기록 1행·활동 2행 유지. 같은 record의 재전달 실험을 대체하지 않음 |
| `PurchaseActivityListenerIntegrationTest` | 실제 listener 설정과 [실패·복구](#listener-실패복구-검증) |
| `PurchaseActivityRedeliveryIntegrationTest` | [DB commit 뒤 offset 미커밋 재전달](#db-commit-뒤-offset-미커밋-재전달-설계) |
| `KafkaPartitionGroupExperimentTest` | [partition·group 배정과 key 매핑](#partitiongroup-실험-설계) |
| `ArchitectureTest` | 계층 의존과 순수 도메인의 JPA·Lombok 의존 금지. Java import 규칙은 Checkstyle이 검사 |

```bash
./gradlew :apps:commerce-api:check :apps:commerce-streamer:check
```

## 로컬 전체 흐름 관찰

Producer와 Consumer의 자동화 검증을 한 테스트에 결합하지 않는다. 실제 PG 성공으로 시작한 주문이 broker를 거쳐 구매 활동 DB에 반영되는 연결은 로컬에서 관찰한다. [로컬 실행 절차](../../verification/order-confirmed-event/README.md)는 격리 환경에서 전송 실패 A·정상 소비 B의 주문·broker·구매 활동을 함께 확인하고, streamer 프로세스 재시작 뒤 행·offset 보존까지 확인한다. 원본 JSON·로그는 로컬에 보관하고 핵심 관찰값은 PR 또는 기술 글에 요약한다.

1. 로컬 Kafka·MySQL·테스트 PG를 준비하고 `commerce-api`와 `commerce-streamer`를 실행한다. 두 애플리케이션이 같은 로컬 Kafka를 사용하고, streamer가 `order-events`를 구독 중인지 확인한다.
2. 기존 주문·결제 API와 테스트 PG 성공 결과 적용 경로로 새 주문을 확정한다. 이벤트를 직접 주입하거나 DB를 직접 수정하지 않는다.
3. 주문 저장 결과에서 `orderId`, 최초 `confirmedAt`, 저장 품목·수량을 기록하고, 계약에 따라 `eventId`를 계산한다.
4. Kafka UI 또는 별도 읽기 전용 consumer group으로 broker record를 확인한다. key가 `orderId` 문자열이고, payload의 ID·확정 시각·상품별 수량이 주문 사실과 같은지, topic·partition·offset이 무엇인지 기록한다. 별도 consumer를 사용해도 `purchase-activity` group의 offset은 변경하지 않는다.
5. streamer의 DB에서 해당 `eventId`·`orderId`의 처리 완료 기록과 품목별 구매 활동을 조회한다. 구매 활동의 상품 집합·수량·`confirmedAt`이 주문과 일치하는지 확인하고, 대상 record의 partition에 대해 group committed offset이 `n + 1` 이상인지 확인한다.

소비와 DB 반영은 비동기이므로 정한 deadline까지 기다린다. deadline 안에 구매 활동이 보이지 않으면 broker record, consumer group 상태와 offset, DB 저장 오류를 차례로 확인한다. 짧은 대기 한 번이나 lag 0만으로 유실을 판정하지 않는다. 이 관찰은 실제 앱 연결을 확인하는 로컬 smoke check이며, 두 애플리케이션을 묶은 자동화 테스트를 추가하는 것은 아니다.

## listener 실패·복구 검증

`PurchaseActivityListenerIntegrationTest`는 실제 등록된 listener와 factory의 설정을 확인한다. 정상 record 완료 뒤 실패 record와 그 다음 record를 같은 partition에 발행하고, 두 번째 품목 저장 오류로 전체 rollback·소비 중단·실패 offset 유지가 되는지 확인한다. 오류 제거 후 같은 container/group을 재시작해 같은 topic·partition·offset을 다시 받고, DB commit 뒤 ack 직전에도 offset이 유지되는지 통제된 대기로 관찰한다. 이후 ack와 뒤 record의 정상 처리까지 확인한다. 이는 DB 실패 복구 검증이며, DB 성공 뒤 offset 미커밋 종료는 아래 재전달 설계에서 따로 검증한다.

이 검증은 handler를 실제 호출하는 listener 스레드와 handler 반환 후의 스레드가 같은지, 반환 시 활성 DB 트랜잭션이 없고 새 조회에는 구매 활동이 보이는지도 확인한다. 실패 지점의 offset을 `n`이라고 할 때 소비 종료 뒤 `n`, 재시작 후 DB commit 뒤 ack 직전에도 `n`, ack 뒤 `n+1`이라는 값을 실제 broker에서 비교한다. 다음 record 진입도 잠시 제어해 이전 record의 ack 결과를 따로 관찰한다.

## DB commit 뒤 offset 미커밋 재전달 설계

`PurchaseActivityRedeliveryIntegrationTest`는 실제 broker·MySQL과 애플리케이션 listener의 `OrderConfirmedParser`·`PurchaseActivityHandler`를 사용한다. record listener factory의 유효 consumer 설정을 복사하고 같은 topic·group·DB에서 consumer만 새로 만든다. 업무 listener는 이 테스트에서 자동 시작하지 않는다.

- target 앞에 준비용 record를 발행해 target offset `n`이 로그의 시작과 다르게 한다. send metadata에서 실제 위치를 얻으며 숫자를 고정하지 않는다.
- 첫 consumer만 배정 callback에서 `n`으로 seek하고 group offset `n`을 동기 commit한다. 종료 callback은 commit하지 않는다. 첫 poll 결과를 실제 handler로 저장하고, `position=n+1`과 별개로 broker의 committed offset이 `n`인지 확인한다.
- 첫 consumer는 완료 offset을 요청하지 않고 유한한 `close(Duration)`로 닫는다. 자동 commit은 false이며 비동기 commit은 사용하지 않는다. 실제 consumer의 호출 이력과 종료 뒤 broker offset을 함께 확인한다.
- 새 consumer는 일반 subscribe·poll만 한다. seek·offset reset 없이 처음 받은 record가 target의 같은 topic·partition·offset인지 검증한다. 시작 위치를 잃어 `earliest`로 돌아갔다면 준비용 record 때문에 이 검증이 실패한다.
- 두 번째 처리도 실제 handler를 호출한다. 별도 DB 트랜잭션의 flush·clear 뒤 완료 기록과 활동의 행 ID·내용·생성/수정 시각이 첫 처리 뒤와 같은지 비교한다. 그 뒤에만 `n+1`을 commit한다.

기대값은 target 전달 2회, 완료 기록 1행·활동 2행 유지, committed offset `n → n → n+1`이다. 테스트에서 계약에 맞게 준비한 OrderConfirmed record를 실제 broker에 발행하고, 실제 parser·handler·DB로 재전달을 검증한다. PG 주문 생성·확정부터 producer 발행까지의 경로는 별도 테스트에서 검증한다. 통제된 close를 사용하므로 OS 강제 종료나 broker 장애를 시험했다고 표현하지 않는다. DB commit 전 오류·전체 rollback·실제 Spring listener 중단·같은 group 재시작은 [listener 실패·복구 검증](#listener-실패복구-검증)에서 검증한다.

```bash
./gradlew :apps:commerce-streamer:test --tests '*PurchaseActivityRedeliveryIntegrationTest' --tests '*PurchaseActivityListenerIntegrationTest'
```

## Partition·group 실험 설계

`KafkaPartitionGroupExperimentTest`는 `modules:kafka` test fixture의 `KafkaTestContainersConfig`를 작은 Spring 테스트 컨텍스트에서 재사용한다. 이 컨텍스트는 Kafka fixture만 로드하며 API·streamer의 업무 listener나 MySQL·Redis를 올리지 않는다. producer·consumer·admin은 fixture가 설정한 실제 `spring.kafka.bootstrap-servers` 주소를 함께 사용한다. 컨테이너 내부 주소나 로컬 Compose 고정 포트를 넣지 않는다.

- 각 실행의 고유 topic을 partition 2개·복제 1개로 생성한다. group도 고유 ID를 사용한다.
- topic 생성 응답과 metadata 조회 가능 시점을 구분한다. 생성 뒤 조회의 재시도 가능한 오류는 정한 기한 안에서 다시 확인하며, partition·복제 수 검증은 그대로 수행한다.
- 같은 group의 consumer를 1→2→3개로 늘린다. 각 consumer의 전용 스레드만 subscribe·poll·groupMetadata·assignment·commit·close를 호출한다. 제어 스레드에서 호출하는 consumer API는 종료를 위한 thread-safe `wakeup`뿐이다.
- broker가 보고한 group `STABLE`, 실제 member ID와 수, 각 consumer가 poll 뒤 보고한 generation·assignment가 일치하는지를 유한하게 기다린다. 같은 배정이 연속 관찰된 뒤 각 partition의 소유자가 한 명인지 검증한다. 1개일 때 두 partition, 2개일 때 하나씩, 3개일 때 적어도 한 member의 빈 배정을 기대한다.
- 각 안정된 단계에서 partition 0·1을 명시해 새 record를 보낸다. 실제 send metadata의 topic·partition·offset을 수신 record·member와 대조한다. 실험 consumer는 읽은 record의 진행 위치만 동기 commit해 다음 rebalance에 이전 단계의 record가 섞이지 않게 한다. 구매 활동 handler는 호출하지 않는다.
- key 실험은 별도 topic에서 partition 인자를 생략한 producer send를 사용한다. 고정된 partition 수에서 같은 key의 metadata partition이 같은지 확인하고, 다른 key 간 partition의 차이는 요구하지 않는다.
- 테스트는 만든 client·consumer 스레드·topic·group을 정리한다. 공유 Kafka 컨테이너는 test fixture/JVM과 Testcontainers가 수명과 최종 정리를 맡으며, 테스트 하나가 다른 테스트의 공유 broker를 중간에 내리지 않는다.

실험 producer는 `acks=all`과 producer 멱등성을 사용한다. 단일 broker·복제 1에서는 현재 ISR이 한 개이므로 다중 replica의 기록, leader 장애의 인계, `min.insync.replicas` 미달 쓰기 거절을 증명하지 않는다. group 배정과 key 매핑 검증은 복제 장애 실험과 구분한다.

```bash
./gradlew :apps:commerce-streamer:test --tests '*KafkaPartitionGroupExperimentTest'
```

## 발행 누락 사례

두 사례로 주문 DB commit과 Kafka 발행 사이의 누락 구간을 확인한다. 다음 단계에서는 Outbox로 이 경계를 개선한다.

| 사례 | 처리 | 완료 시 남길 관찰 |
| --- | --- | --- |
| 누락 | 확정 commit 뒤 send 직전에 실패를 주입 | 주문 `CONFIRMED`와 예약 소비(재고 유지), 해당 `eventId`의 broker record 없음 |
| 정상 | 발행 성공 확인 | 원본 주문·예약 상태, record의 topic·partition·offset·payload |

- 고정 Clock과 품목·수량으로 두 주문을 만들고 주문·예약 상태와 broker record를 대조한다. 카드 정보와 비밀 값은 기록하지 않는다.
- 미발행 확인은 고유 topic의 partition별 end offset을 기준으로 정해진 기한 안에 대상 `eventId`가 없는지 확인한다. 짧은 poll 한 번이나 lag 0만으로 판정하지 않는다.
- `OrderConfirmedKafkaDeliveryIntegrationTest`는 실제 주문 API·테스트 PG·reconcile 경로에서 send 직전 실패와 정상 발행을 확인한다. 미발행 사례는 새 DB 트랜잭션에서 주문 확정이 남았음을 확인한 뒤 테스트 spy가 send 진입을 차단한다. 정상 사례는 broker metadata와 payload를 검증한다.
- send 직전 실패 주입은 프로세스 강제 종료가 아니다. DB와 broker가 원자적으로 묶이지 않은 경계를 보여주며, send timeout 뒤 broker에 기록됐는지까지 증명하지 않는다.

동일 두 주문의 구매 활동 결과는 [격리 로컬 실행](../../verification/order-confirmed-event/README.md)에서 추가로 확인한다. 기대값은 topic 크기 제한에 거절된 A의 CONFIRMED·완료 기록 0행·활동 0행, 제한 복구 후 정상 전송한 B의 완료 기록 1행·활동 2행이다. 이 실행의 broker 거절과 위 테스트의 send 직전 차단은 서로 다른 실패 주입이다.
