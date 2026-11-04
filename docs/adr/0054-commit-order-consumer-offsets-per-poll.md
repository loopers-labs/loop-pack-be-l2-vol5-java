# ADR 0054: 주문 구매 활동은 동기 처리 후 BATCH로 소비 위치를 기록한다

Status: Accepted
Supersedes: [ADR 0046](0046-acknowledge-order-records-with-manual-immediate.md)
Extends: [ADR 0049](0049-connect-order-consumer-error-handler.md)의 완료 위치 연결. 오류 분류·중단 범위는 유지한다.
Related: [ADR 0039](0039-record-order-confirmation-and-purchases-atomically.md), [ADR 0053](0053-retry-transient-consumer-failures-for-minutes-before-stopping.md), [비교 실험](../week6/kafka-settings-experiments.md#batch-ack-experiment)

## Context

구매 활동 listener는 주문 한 건의 DB 처리가 끝날 때까지 같은 consumer 스레드에서 기다린다. 완료 기록과 모든 품목은 한 DB TX에 저장하고, 같은 사실이 다시 오면 추가 저장 없이 끝난다.

처음에는 DB 처리 뒤 직접 ack하는 MANUAL_IMMEDIATE를 골랐다. 이후 한 poll의 첫 주문을 저장하고 중단하는 비교에서 BATCH는 그 주문을 다시 읽었지만, 두 방식의 최종 DB는 완료 기록 3·구매 활동 6행으로 같았다. DB 효과를 한 번만 남긴 것은 기존 멱등 처리였다. 사용자는 이 비교와 비용을 검토한 뒤 BATCH로 변경하기로 했다.

## Decision

- 주문용 factory의 ack mode를 BATCH로 한다. record listener와 주문별 동기 DB TX는 유지한다. 메시지를 List로 받는 batch listener나 묶음 insert로 바꾸는 결정은 아니다.
- OrderConfirmedKafkaListener는 Acknowledgment를 받지 않고 OrderConfirmedRecordAdapter.handle(record)를 호출한다. DB commit 또는 확인된 동일 사실 no-op 뒤 정상 반환한다. 정상 경로의 위치는 container가 poll 처리 경계에서 기록한다.
- 파싱·DB 처리 예외는 전파한다. 완료 여부를 DB commit 이전에 반환하거나 예외를 삼켜 성공으로 만들지 않는다.
- 업무용 DefaultErrorHandler는 ackAfterHandle=true·commitRecovered=false로 둔다. DLT 발행 성공 뒤 recoverer가 정상 반환하면 container가 완료 위치를 관리한다. commitRecovered=true는 MANUAL_IMMEDIATE용 직접 commit 경로이므로 사용하지 않는다.
- DLT 전송 실패·DB 재시도 소진은 기존과 같이 예외로 끝나며 이 앱의 주문 listener 전체를 중단한다. 실패한 record를 지나지 않고, 원인 해결 뒤 같은 group으로 다시 읽는다. 이미 성공한 앞 record의 위치는 오류 처리 전에 commit될 수 있다.
- DB commit과 Kafka offset commit은 원자적이지 않다. 중단 후 더 넓은 구간이 재전달될 수 있으며, 기존 DB 유일 제약·사실 대조·no-op으로 구매 중복을 막는다.
- 자동 commit 끔·동기 commit·배정 commit 끔·earliest·group·concurrency·producer·DLT 대기·재시도 시간은 유지한다. 공유 demo용 batch factory의 MANUAL도 변경하지 않는다.
- 어댑터의 두 인자 수동 ack 메서드는 기존 MANUAL/IMMEDIATE 비교 실험의 호환을 위해 남긴다. 업무용 listener는 한 인자 메서드만 사용한다.

## Rationale and alternatives

BATCH는 현재의 동기 DB 완료와 멱등 처리에 맞춰, 업무용 listener가 직접 ack 호출을 관리하는 책임을 줄인다. 한 poll에 여러 record가 있으면 record마다 commit하는 방식보다 commit 호출을 모을 수 있다. 실제 처리량·지연·commit 횟수는 이번 비교에서 측정하지 않았으므로 성능 향상을 검증했다고 주장하지 않는다.

MANUAL_IMMEDIATE를 유지하면 중단 뒤 다시 읽는 범위를 줄일 수 있다. 재전달의 비용이 커지면 재검토할 수 있지만, 이 방식도 DB commit 뒤 ack 전 중단의 재전달을 없애지는 못한다. BATCH로 바꾼 뒤에도 업무 효과를 보호하는 멱등 처리는 필요하다.

MANUAL도 완료 위치를 모으지만 수동 ack 호출을 계속 관리한다. RECORD는 정상 반환한 record마다 commit해 재전달 범위를 줄이는 대안이다. 이번에는 동기 handler의 정상 반환을 완료 신호로 사용하고 poll 단위로 모으는 BATCH를 선택했다.

## Evidence and limits

- 선택 근거: [BATCH / MANUAL_IMMEDIATE 비교](../week6/kafka-settings-experiments.md#batch-ack-experiment). 같은 DB 결과와 다른 재전달 범위를 확인한 당시 기록을 보존한다.
- 실제 업무용 listener의 정상·논리적 중복·DB commit 뒤 같은 offset 재전달, 업무용 오류 처리기의 재시도·DLT·중단/복구와 실행 결과는 [구현 기록 37절](../week6/implementation-log.md#batch-adoption)에 둔다.
- 기존의 수동 ack 실험은 당시 조건으로 유지하며, 현재 업무용 BATCH 검증과 구분한다. 단일 broker·consumer의 통제된 중단을 운영 전체의 보장으로 일반화하지 않는다.
- 근거: [Spring Kafka commit 방식](https://docs.spring.io/spring-kafka/reference/3.3/kafka/receiving-messages/message-listener-container.html#committing-offsets), [오류 처리와 recovered record](https://docs.spring.io/spring-kafka/reference/3.3/kafka/annotation-error-handling.html).
