# ADR 0040: 수신 파싱과 DB 연결을 준비하고 소비 설정 선택 전에는 자동 구독하지 않는다

Status: Accepted
Related: [ADR 0039](0039-record-order-confirmation-and-purchases-atomically.md), [week6 설계](../week6/design.md#consumer-preparation)

Experiments: 첫 읽기 위치·같은 group의 consumer 수·DB 저장 실패 처리 비교와 DB commit 뒤 재전달 검증은 [Kafka 설정 비교 실험](../week6/kafka-settings-experiments.md)에 둔다. 실험용 값과 업무용 선택·반영 상태는 구분한다.

Follow-up: 메시지 오류의 DLQ 분리와 DB 재시도 소진 후 중단 정책은 [ADR 0041](0041-separate-invalid-messages-from-db-retry-exhaustion.md)에서 확장한다. 이 ADR의 수신 준비·기본 비활성 결정은 Accepted를 유지하며 아래 본문은 당시의 범위를 보존한다.

Follow-up: 첫 읽기 위치 earliest와 partition 3개·동일 group의 활성 consumer 3개 선택은 [ADR 0044](0044-adopt-earliest-and-three-order-consumers.md)에 반영했다. 실제 업무용 구독을 활성화한 변경은 아니며 이 ADR의 Accepted 상태는 유지한다.

Follow-up (ack): 당시 [ADR 0046](0046-acknowledge-order-records-with-manual-immediate.md)에서 MANUAL_IMMEDIATE를 선택했고, 현재는 [ADR 0054](0054-commit-order-consumer-offsets-per-poll.md)의 BATCH로 대체했다. 이 ADR의 기존 결정은 Accepted를 유지하며 아래 본문은 결정 당시 기록으로 보존한다.

Implementation (listener): [조건부 주문용 listener·factory 연결](../week6/implementation-log.md#order-listener-wiring). 기본 비활성과 기존 결정은 유지하며 아래 본문은 당시 기록으로 보존한다.

Follow-up (local): [ADR 0050](0050-create-order-topics-in-compose-and-enable-local-events.md)에서 local profile에서만 주문 소비를 켠다. 기본값·test profile의 자동 구독 없음은 Accepted로 유지한다.

## Context

DB handler의 완료 이벤트 기록·구매 활동 저장·동일 사실 no-op은 구현했다. 사용자는 Kafka 소비 시작 위치 등의 설정을 기본 준비 이후에 하나씩 정하고 싶어 한다. group·offset reset·ack mode·실패 처리 값을 대신 골라 활성화하면 그 선택 전에 메시지를 읽거나 처리 위치가 남을 수 있다.

수신 계약을 검사하고 DB handler로 전달하는 코드는 설정 선택과 분리해 검증할 수 있다. 기존 demo는 공통 batch factory를 자동 구독하므로 준비 상태에서 실행되지 않도록 명시적인 기본 비활성이 필요하다.

## Decision

- `OrderConfirmedMessageParser`가 String key와 JSON bytes를 검증한다. eventType=OrderConfirmed, 정수 schemaVersion=1, 비어 있지 않은 eventId, 양의 정수 orderId·productId·quantity와 품목 배열을 요구한다. 숫자 문자열·소수·범위 초과를 조용히 정수로 바꾸지 않는다.
- key는 orderId의 문자열 표현과 정확히 일치해야 한다. confirmedAt은 Instant로 읽고, producer가 저장된 주문 시각을 복사하는 마이크로초 정밀도를 확인한다. MySQL datetime(6)에 담을 수 없는 소수 정밀도를 조용히 잃지 않는다. 연속된 JSON 문서·빈 본문·잘못된 시각도 실패시킨다.
- parser는 알려진 계약 필드를 읽으며 추가 JSON 필드는 업무 사실에 사용하지 않는다. 다른 eventType·schemaVersion을 수용하는 버전 확장 정책은 채택하지 않는다.
- 수신 계층은 application의 `OrderConfirmationCommand`를 사용한다. command 안에서 기존 domain 입력을 구성해 정규화·업무 불변식을 재사용하고, domain 객체는 application 내부에서만 꺼낸다. 기존 계층 의존 규칙을 유지한다.
- `OrderConfirmedRecordAdapter`는 전달받은 ConsumerRecord를 parser와 DB handler에 연결하고 예외를 전파한다. 이 단계에는 `@KafkaListener`, 업무용 listener factory, group, ack·offset commit을 등록하지 않는다.
- 기존 demo는 `demo-kafka.enabled=true`일 때만 등록한다. application.yml의 기본값은 false다. 공유 batch factory의 설정은 변경하지 않는다.
- application.yml의 공통 YAML import를 `classpath:`로 명시한다. broker·topic 설정이나 producer의 wire 계약을 변경하지 않는다.

## Options considered

| 준비 방식 | 판단 |
| --- | --- |
| 기존 group·latest·batch 기본값으로 즉시 소비 | 설정 선택 전에 소비가 진행되며 주문별 DB 경계·실패 처리가 아직 연결되지 않음 |
| 임의의 새 group·earliest·ack/error handler를 골라 활성화 | 사용자가 하나씩 고르려는 범위를 대신 결정함 |
| parser·수신 어댑터와 DB 경로를 준비하고 자동 구독은 보류 — 채택 | 소비 정책을 미리 고르지 않고 계약·DB 연결·자동 구독 없는 기본 상태를 검증할 수 있음 |

## Consequences

기본 실행 상태에서 demo나 주문용 Kafka 구독은 없다. parser·어댑터는 Spring bean이며 테스트에서는 직접 호출할 수 있지만, 실제 poll이나 offset 변경을 수행하지 않는다. 기본 준비가 끝났다는 말은 Kafka listener 연결까지 끝났다는 뜻이 아니다.

사용자가 소비 설정을 선택한 뒤 별도 record listener factory와 실제 listener를 등록해야 한다. 그때 DB commit 또는 검증된 no-op 뒤 ack, 오류 시 실패 record의 offset 보호, 실제 broker 재전달을 검증한다. 추가 JSON 필드의 처리·버전 확장도 실제 계약 변경 때 다시 다룬다.

## Evidence

- [수신 parser](../../apps/commerce-streamer/src/main/java/com/loopers/interfaces/consumer/OrderConfirmedMessageParser.java), [수신 어댑터](../../apps/commerce-streamer/src/main/java/com/loopers/interfaces/consumer/OrderConfirmedRecordAdapter.java)
- [application 입력](../../apps/commerce-streamer/src/main/java/com/loopers/application/purchase/OrderConfirmationCommand.java), [demo 기본 비활성](../../apps/commerce-streamer/src/main/java/com/loopers/interfaces/consumer/DemoKafkaConsumer.java)
- [자동 구독 없는 설정 검증](../../apps/commerce-streamer/src/test/java/com/loopers/interfaces/consumer/ConsumerPreparationConfigurationTest.java)
- [실행 결과와 한계](../week6/implementation-log.md#consumer-preparation)
