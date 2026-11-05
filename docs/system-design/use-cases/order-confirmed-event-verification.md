# 주문 확정 이벤트 검증

> [주문 확정 이벤트](order-confirmed-event.md)의 [검증 기준](order-confirmed-event.md#검증-기준)을 확인하는 테스트 위치와 실험 설계

기대값은 설계 문서의 검증 기준을 따르고, 이 문서는 어떤 테스트가 그것을 어떻게 확인하는지 정리한다.

구현 정책은 [Producer](../../../apps/commerce-api/docs/order-confirmed-producer.md), [Broker](../../../modules/kafka/docs/broker.md), [Consumer](../../../apps/commerce-streamer/docs/purchase-activity-consumer.md)의 모듈 문서를 따른다.

## 검증 위치

commerce-api는 확정 시각 저장·Outbox 원자성·Relay 발행을, commerce-streamer는 계약 해석과 구매 활동 소비를 각자의 테스트로 검증한다. 두 앱을 한 테스트에 묶지 않는다. 이벤트 계약은 [JSON 예시](order-confirmed-event.md#이벤트-계약)를 기준으로 producer는 생성 결과를, consumer는 해석 결과를 검증하고, 확정부터 구매 활동까지의 전체 연결은 실제 앱을 함께 띄운 로컬 실행으로 확인한다.

| commerce-api 테스트 | 검증 |
| --- | --- |
| `OrderConfirmedEventTest` | 주문 ID로 유도한 eventId, 저장된 확정 시각, 상품 ID 오름차순 품목으로 이벤트 구성. 미확정 주문, 확정 시각이나 원본 품목이 없는 주문은 거절 |
| `OrderConfirmedOutboxBackfillServiceTest` | 원본 품목이 없으면 복구를 거절하고 Outbox 저장소를 호출하지 않음 |
| `OrderConfirmedMessageV1Test` | 계약 필드만 담은 V1 JSON과 orderId 문자열 key. 다른 시간대의 확정 시각을 마이크로초까지 UTC로 표현 |
| `PaymentConfirmedAtIntegrationTest` | 최초 PG 성공에 주입한 Clock 시각을 마이크로초로 잘라 저장. 중복 성공에도 처음 시각 유지, 실패 종결에는 확정 시각 없음 |
| `OrderConfirmedAtMigrationIntegrationTest` | 컬럼 추가 뒤 기존 주문의 확정 시각은 비워 두고 상태·결제 값·수정 시각은 바꾸지 않음 |
| `OrderConfirmedOutboxAtomicityTest` | 최초 성공 때 주문·시도·Outbox를 같은 TX에 저장. 비성공·중복 결과, 직렬화·유일 제약·commit 실패 rollback, 동시 성공에서 행 하나와 고정 payload 확인 |
| `OrderConfirmedKafkaDeliveryIntegrationTest` | 실제 주문 API·테스트 PG·MySQL·Kafka에서 Relay의 저장 payload 재시도, BLOCKED 후 다음 행 진행, 수동 재개와 누적 시도 보존, broker 기록 뒤 상태 저장 실패와 두 대기 방식 비교. 두 방식의 관찰 timeout 뒤 늦은 성공·종료 이벤트 뒤 producer 오류·같은 메시지 재발행, TX1만 세 번 commit한 뒤 네 번째 send 차단, PUBLISHED 선저장의 누락 반례도 확인 |

| commerce-streamer 테스트 | 검증 |
| --- | --- |
| `OrderConfirmationTest` | 양수 규칙, 동일 상품 수량 합산·정렬, overflow 거절과 불변 목록 |
| `OrderConfirmedParserTest` | JSON 속성·품목 순서에 무관한 비교, key 관계, 버전·필드 타입·필수 값 검증. 중복 JSON 필드와 뒤에 붙은 추가 JSON도 거절 |
| `OrderConfirmedVersionDispatchTest` | 여러 해석기의 버전별 선택·기존 버전 유지, 미지원 버전과 중복 등록 거절. listener 메서드를 직접 호출해 미지원 버전의 handler·ack 미호출을 확인하는 단위 테스트. 실제 컨테이너 중단·broker offset의 통합 검증과 구분. 추가 버전 선택은 테스트 대역으로 검증하며 실제 V2 명세를 정의한 것은 아님 |
| `PurchaseActivityIntegrationTest` | 실제 MySQL의 행·수량·마이크로초 확정 시각, ID·payload 충돌, 중간 품목 오류의 전체 rollback, 각 유일 제약과 동시 경쟁 뒤 새 TX 판정. 저장 뒤 flush·clear한 새 조회와 별도 SQL로 확인 |
| `PurchaseActivityRepositoryTest` | DB 오류 코드에 의존하지 않고 완료 기록의 알려진 유일 제약만 중복으로 변환. 한정자·대소문자 처리, 미상·다른 제약과 비유일성 오류의 원래 예외 유지 |
| `KafkaClientConfigTest` | consumer의 byte 배열 값과 auto commit off, batch factory의 `kafka.batch-listener` 값 적용, producer의 `acks=all`·멱등성, producer·consumer·admin의 같은 broker 주소와 fixture broker 왕복 |
| `PurchaseActivityKafkaIntegrationTest` | 실제 broker의 JSON record를 실제 Spring listener로 저장한 뒤 group committed offset 확인. 같은 사실을 새 record로 다시 보내도 완료 기록 1행·활동 2행 유지. 같은 record의 재전달 실험을 대체하지 않음 |
| `PurchaseActivityListenerIntegrationTest` | 실제 listener·record factory 설정(batch 전용 poll·session 설정 비상속, batch factory의 YAML 반영 포함)과 [실패·복구](#listener-실패복구-검증) |
| `PurchaseActivityRedeliveryIntegrationTest` | [DB commit 뒤 offset 미커밋 재전달](#db-commit-뒤-offset-미커밋-재전달-설계) |
| `KafkaPartitionGroupExperimentTest` | [partition·group 배정과 key 매핑](#partitiongroup-실험-설계) |
| `ArchitectureTest` | 계층 의존과 순수 도메인의 JPA·Lombok 의존 금지. Java import 규칙은 Checkstyle이 검사 |

```bash
./gradlew :apps:commerce-api:check :apps:commerce-streamer:check
```

## 로컬 전체 흐름 관찰

실제 PG 성공으로 시작한 주문이 Outbox와 Relay를 거쳐 Kafka·구매 활동 DB에 반영되는 연결은 로컬에서 관찰한다. [로컬 실행 절차](../../verification/order-confirmed-event/README.md)는 격리 환경에서 직접 발행의 누락 상태를 재현하고, 저장된 주문 사실로 Outbox를 보충한 뒤 Relay와 Consumer 결과를 확인한다. 실패한 대상의 `CONFIRMED` 상태·재고 예약은 유지되고, 구매 활동이 누락 주문에는 생성되며 이미 반영된 주문에는 중복 효과가 생기지 않는지 확인한다. 원본 JSON·로그는 로컬에 보관하고 핵심 관찰값은 PR 또는 기술 글에 요약한다.

Compose 환경에서 현재 정상 흐름을 직접 관찰할 때의 순서는 다음과 같다. 누락 상태 재현·복구는 위 실행 도구가 고유한 topic·group과 격리 broker를 사용해 별도로 실행한다.

1. 로컬 Kafka·MySQL·테스트 PG를 준비하고 `commerce-api`와 `commerce-streamer`를 실행한다. 두 애플리케이션이 같은 로컬 Kafka를 사용하고, streamer가 `order-events`를 구독 중인지 확인한다.
2. 기존 주문·결제 API와 테스트 PG 성공 결과 적용 경로로 새 주문을 확정한다. 이벤트를 직접 주입하거나 DB를 직접 수정하지 않는다.
3. 주문 저장 결과에서 `orderId`, 최초 `confirmedAt`, 저장 품목·수량을 기록하고, 계약에 따라 `eventId`를 계산한다.
4. Kafka UI 또는 별도 읽기 전용 consumer group으로 broker record를 확인한다. key가 `orderId` 문자열이고, payload의 ID·확정 시각·상품별 수량이 주문 사실과 같은지, topic·partition·offset이 무엇인지 기록한다. 별도 consumer를 사용해도 `purchase-activity` group의 offset은 변경하지 않는다.
5. streamer의 DB에서 해당 `eventId`·`orderId`의 처리 완료 기록과 품목별 구매 활동을 조회한다. 구매 활동의 상품 집합·수량·`confirmedAt`이 주문과 일치하는지 확인하고, 대상 record의 partition에 대해 group committed offset이 `n + 1` 이상인지 확인한다.

소비와 DB 반영은 비동기이므로 정한 deadline까지 기다린다. deadline 안에 구매 활동이 보이지 않으면 broker record, consumer group 상태와 offset, DB 저장 오류를 차례로 확인한다. 짧은 대기 한 번이나 lag 0만으로 유실을 판정하지 않는다. 이 관찰은 실제 앱 연결을 확인하는 로컬 smoke check다.

## listener 실패·복구 검증

`PurchaseActivityListenerIntegrationTest`는 실제 등록된 listener·factory 설정을 확인하고 DB 실패 복구를 검증한다. 정상 record 완료 뒤 실패 record와 그 다음 record를 같은 partition에 발행하고, 실패 offset을 `n`이라 할 때 실제 broker·DB에서 다음을 확인한다.

- 두 번째 품목 저장 오류: 전체 rollback, 소비 중단, committed offset `n`
- 오류 제거 후 같은 container/group 재시작: 같은 topic·partition·offset 재수신, DB commit 뒤 ack 직전(통제된 대기)에도 `n`, ack 뒤 `n+1`. 다음 record 진입을 잠시 막아 앞 record의 ack 결과를 따로 관찰한 뒤 정상 처리
- handler 반환: handler를 호출한 listener 스레드와 같은 스레드, 활성 DB 트랜잭션 없음, 새 조회에 구매 활동이 보임

DB commit 뒤 offset 미커밋 종료는 아래 [재전달 설계](#db-commit-뒤-offset-미커밋-재전달-설계)에서 따로 검증한다.

## DB commit 뒤 offset 미커밋 재전달 설계

`PurchaseActivityRedeliveryIntegrationTest`는 실제 broker·MySQL과 애플리케이션 listener의 `OrderConfirmedParser`·`PurchaseActivityHandler`를 사용한다. record listener factory의 유효 consumer 설정을 복사하고 같은 topic·group·DB에서 consumer만 새로 만든다. 업무 listener는 이 테스트에서 자동 시작하지 않는다.

- target 앞에 준비용 record를 발행해 target offset `n`이 로그의 시작과 다르게 한다. send metadata에서 실제 위치를 얻으며 숫자를 고정하지 않는다.
- 첫 consumer만 배정 callback에서 `n`으로 seek하고 group offset `n`을 동기 commit한다. 종료 callback은 commit하지 않는다. 첫 poll 결과를 실제 handler로 저장하고, `position=n+1`과 별개로 broker의 committed offset이 `n`인지 확인한다.
- 첫 consumer는 완료 offset을 요청하지 않고 유한한 `close(Duration)`로 닫는다. 자동 commit은 false이며 비동기 commit은 사용하지 않는다. 실제 consumer의 호출 이력과 종료 뒤 broker offset을 함께 확인한다.
- 새 consumer는 일반 subscribe·poll만 한다. seek·offset reset 없이 처음 받은 record가 target의 같은 topic·partition·offset인지 검증한다. 시작 위치를 잃어 `earliest`로 돌아갔다면 준비용 record 때문에 이 검증이 실패한다.
- 두 번째 처리도 실제 handler를 호출한다. 별도 DB 트랜잭션의 flush·clear 뒤 완료 기록과 활동의 행 ID·내용·생성/수정 시각이 첫 처리 뒤와 같은지 비교한다. 그 뒤에만 `n+1`을 commit한다.

기대값은 target 전달 2회, 완료 기록 1행·활동 2행 유지, committed offset `n → n → n+1`이다. 통제된 close를 사용하므로 OS 강제 종료나 broker 장애를 시험했다고 표현하지 않는다.

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

실험 producer는 `acks=all`·멱등성을 쓰지만 이 실험은 group 배정과 key 매핑만 확인한다. 복제 장애의 한계는 [Broker 설계](../../../modules/kafka/docs/broker.md#보존과-복제의-한계)를 따른다.

```bash
./gradlew :apps:commerce-streamer:test --tests '*KafkaPartitionGroupExperimentTest'
```

## 직접 발행에서 관찰한 누락 기준선

이 절은 Outbox 도입 전 직접 발행 흐름에서 관찰한 누락 상태를 기준선으로 보존한다. 현재 경로는 주문 확정과 Outbox 기록을 같은 TX에 넣고 Relay가 발행한다. 아래 사례는 누락 경계를 재현하거나 원래 직접 발행 구현을 비교할 때 사용한다.

| 사례 | 처리 | 완료 시 남길 관찰 |
| --- | --- | --- |
| 누락 | 확정 commit 뒤 send 직전에 실패를 주입 | 주문 `CONFIRMED`와 예약 소비(재고 유지), 해당 `eventId`의 broker record 없음 |
| 정상 | 발행 성공 확인 | 원본 주문·예약 상태, record의 topic·partition·offset·payload |

- 고정 Clock과 품목·수량으로 두 주문을 만들고 주문·예약 상태와 broker record를 대조한다. 카드 정보와 비밀 값은 기록하지 않는다.
- 미발행 확인은 고유 topic의 partition별 end offset을 기준으로 정해진 기한 안에 대상 `eventId`가 없는지 확인한다. 짧은 poll 한 번이나 lag 0만으로 판정하지 않는다.
- 직접 발행 경로의 미발행 사례는 [검증 실행 도구](../../verification/order-confirmed-event/run.py)로 재현한다. 테스트는 주문 API·테스트 PG로 실제 주문을 확정하고, broker 발행 전 실패 사례와 정상 발행 사례의 DB·Kafka 상태를 대조한다.
- 현재 Relay의 재시도·broker 기록 뒤 상태 저장 실패·Future 대기 방식 비교는 `OrderConfirmedKafkaDeliveryIntegrationTest`가 검증한다. 이 통합 테스트는 로컬 DB 전체를 정리하므로 격리된 MySQL에서만 실행한다.
- send 직전 실패 주입은 프로세스 강제 종료가 아니다. DB와 broker가 원자적으로 묶이지 않은 경계를 보여주며, send timeout 뒤 broker에 기록됐는지까지 증명하지 않는다.

같은 두 사례의 구매 활동 결과는 [로컬 전체 흐름 관찰](#로컬-전체-흐름-관찰)의 실행 도구로 확인한다. 그 실행의 broker 거절과 위 테스트의 send 직전 차단은 서로 다른 실패 주입이다.
