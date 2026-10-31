# ADR 0046: 주문 확정 record는 DB 처리 후 MANUAL_IMMEDIATE로 ack한다

Status: Superseded — [ADR 0054](0054-commit-order-consumer-offsets-per-poll.md)가 주문용 ack 방식을 BATCH로 대체한다. 아래 본문과 당시 검증은 보존한다.
Related: [ADR 0040](0040-prepare-order-event-receiving-without-auto-subscription.md), [ADR 0041](0041-separate-invalid-messages-from-db-retry-exhaustion.md), [ADR 0044](0044-adopt-earliest-and-three-order-consumers.md), [ADR 0045](0045-use-purchase-activity-consumer-group.md), [week6 설계](../week6/design.md#consumer-ack-mode)

Implementation: [DB 처리 후 ack 어댑터 구현](../week6/implementation-log.md#ack-adapter-implementation). 아래 결정 본문과 당시 검증 범위는 보존한다.

Implementation (listener): [조건부 주문용 listener·factory 연결](../week6/implementation-log.md#order-listener-wiring). 기본 비활성과 기존 결정은 유지하며 아래 본문은 당시 기록으로 보존한다.

## Context

공통 batch listener에는 MANUAL이 설정되어 있지만 실제 구매 활동용 listener는 등록하지 않았다. 기존 격리 실험은 주로 RECORD를 관찰 조건으로 사용했다. 사용자는 producer의 acks와 consumer의 ack가 다른 확인임을 이해하고, MANUAL과 MANUAL_IMMEDIATE의 기록 시점·장단점을 비교한 뒤 주문용 시작값으로 MANUAL_IMMEDIATE를 선택했다.

DB commit 뒤 offset을 진행하는 원칙은 이미 정했다. 이번 결정은 그 원칙을 주문 record listener의 ack 방식으로 구체화한다. 기존 기본 factory 값을 주문용 정책으로 자동 승계하지 않는다.

## Decision

- 구매 활동용 record listener의 ack mode는 MANUAL_IMMEDIATE로 한다. consumer 스레드에서 기존 parser·DB handler를 동기로 호출한다.
- 완료 기록과 모든 품목의 DB TX가 commit되어 handler가 정상 반환한 뒤 acknowledge()를 호출한다. 같은 사실로 검증된 정상 no-op도 처리 완료로 ack한다.
- 파싱·DB 저장·사실 충돌이 예외로 전파되면 정상 경로의 ack를 호출하지 않는다. 메시지 오류의 DLQ와 DB 재시도 소진 시 중단 정책은 ADR 0041을 유지하며 상세 분류·DLQ recoverer 연결은 후속 작업이다.
- DB commit과 Kafka offset commit은 별개다. 그 사이 중단에 따른 재전달을 허용하고 기존 DB 멱등 처리로 구매 효과를 보호한다.
- 기존 공통 batch factory의 MANUAL과 demo는 유지한다. 주문용 factory·listener의 등록·활성화는 남은 오류 처리 구성을 정한 뒤 진행한다. 이 변경은 정책 채택과 격리 검증이며 업무용 자동 구독을 켜지 않는다.

## Rationale and alternatives

MANUAL은 ack한 위치를 poll 처리 경계에서 모아 commit하므로 여러 record의 commit 요청을 줄이는 데 유리할 수 있다. commit 전 갑자기 종료되면 이미 DB에 저장한 record의 재전달 범위가 커질 수 있다.

MANUAL_IMMEDIATE는 consumer 스레드에서 주문 하나의 DB 처리가 끝난 뒤 해당 ack의 offset commit을 바로 요청해 경계를 확인하기 쉽다. 각 record마다 동기 commit하면 요청과 대기 비용이 늘 수 있다. 이번 실험은 처리량·지연의 최적값을 증명하지 않으며 DB와 Kafka를 원자적으로 묶지도 않는다.

RECORD는 listener의 정상 반환 뒤 container가 commit하는 대안이다. 기존 실험에서 사용했지만 이번에는 사용자 선택에 따라 코드의 명시적인 ack를 사용한다. MANUAL_IMMEDIATE를 다른 스레드에서 호출하는 방식은 이 결정의 범위가 아니다.

## Evidence and limits

- [ack 비교·실패·재전달 실험](../week6/kafka-settings-experiments.md#manual-ack-experiment): 실제 Kafka·MySQL에서 ack 호출 후 listener 반환 전 committed offset이 MANUAL=0, MANUAL_IMMEDIATE=1인 것을 확인했다.
- 두 방식 모두 DB 실패 세 번 뒤 ack 0회·완료 기록 0·활동 0·offset 0에서 중단했다. 원인 제거·같은 group 재시작 뒤 두 주문을 저장하고 offset 2까지 진행했다.
- MANUAL_IMMEDIATE의 DB commit 후 ack 전 중단에서도 같은 offset 0이 재전달됐다. 기존 행·구매 수량을 유지한 no-op 뒤 ack하여 위치가 0→0→1로 진행했다.
- 실험은 자동 commit 끔·동기 commit·단일 partition/RF1의 격리 조건이다. 상세 실행 결과는 [구현 기록](../week6/implementation-log.md#manual-immediate-adoption)에 둔다. 업무용 factory·전체 구독 구성, MANUAL_IMMEDIATE와 DLQ recoverer의 결합, 처리량 비교는 이 실행에서 검증하지 않았다.
- 동작 기준: [Spring Kafka의 offset commit 설명](https://docs.spring.io/spring-kafka/reference/3.3/kafka/receiving-messages/message-listener-container.html#committing-offsets).
