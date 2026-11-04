# ADR 0047: 같은 주문 확정 이벤트의 다른 사실은 DLQ로 분리한다

Status: Accepted — 분류 정책 채택, 업무용 error handler 연결은 ADR 0049
Related: [ADR 0039](0039-record-order-confirmation-and-purchases-atomically.md), [ADR 0041](0041-separate-invalid-messages-from-db-retry-exhaustion.md), [ADR 0046](0046-acknowledge-order-records-with-manual-immediate.md), [week6 설계](../week6/design.md#consumer-conflict-handling)

Follow-up (DLQ failure): [ADR 0048](0048-stop-consumption-on-dlq-publication-failure-and-resume-manually.md)에서 DLQ 전송 실패 시 중단·사람의 복구 후 같은 group 재시작을 선택한다. 기존 결정은 Accepted로 유지하며 아래 본문은 당시 기록으로 보존한다.

Follow-up (error handler): [ADR 0049](0049-connect-order-consumer-error-handler.md)에서 재시도 분류·중단 범위·DLQ topic을 정하고 업무용 오류 처리기를 연결한다. 기존 결정은 Accepted를 유지하며 아래 본문은 당시 기록으로 보존한다.

## Context

같은 eventId·orderId에 저장된 수량 등과 다른 사실이 오면 기존 DB handler는 원래 사실을 유지하고 충돌 예외를 전파한다. ADR 0041은 이 오류의 소비 대응을 미뤘다. 사용자는 격리 비교 결과와 DLQ 분리 권고를 확인하고 구현을 이어 가도록 동의했다.

정상 주문 42, 수량이 다른 충돌 record, 정상 주문 43을 차례로 보낸 비교에서 두 후보 모두 기존 42를 보호했다. DLQ 후보는 충돌 원문을 보관하고 43도 처리했으며 중단 후보는 43을 처리하지 못하고 원인 해결 없는 재시작에서도 같은 충돌로 멈췄다.

## Decision

- 같은 eventId 또는 orderId로 들어온 사실이 기존 완료 기록과 불일치하면 기존 정상 구매 사실을 변경하지 않는다. 현재 OrderConfirmationConflictException으로 드러나는 충돌을 메시지 문제로 분류해 DLQ로 분리하고 뒤의 정상 주문 처리를 이어 간다.
- 이 충돌을 일시적인 DB 저장 오류의 재시도 대상으로 취급하지 않는다. 같은 식별자의 동일 사실은 기존 정상 no-op 후 ack를 유지한다.
- DLQ의 전송 성공을 확인한 뒤에만 해당 원본 record를 지나가도록 offset을 진행한다. DLQ 전달은 충돌 사실을 DB에 반영했다는 뜻이 아니며 추가 완료 기록·구매 활동을 만들지 않는다.
- DB 일시 오류의 제한 재시도·소진 시 중단 정책과 MANUAL_IMMEDIATE 선택은 유지한다. 기존 DB handler의 충돌 검증을 완화하지 않는다.
- DLQ 발행 실패 시 중단 또는 자동 재시도, 업무용 DLQ topic·보존·운영 재처리와 실제 error handler 연결은 별도로 구체화한다. 테스트용 topic 이름이나 DLQ 실패 시 임시 중단 조건을 자동 채택하지 않는다.

## Rationale and alternatives

DLQ 분리는 기존 사실을 보호하면서 해당 record를 원문·출처와 함께 조사 대상으로 남기고 정상 주문의 처리를 이어 갈 수 있다. 소비 중단은 조사 동안 해당 범위의 진행을 멈추는 대안이나, 같은 충돌이 남아 있으면 재시작만으로 정상 주문이 진행되지 않는다.

다른 이벤트도 신뢰할 수 있는지와 오류의 범위는 관찰·운영 판단이 필요하다. 이번 결정이 경보·원인 분석·재처리 운영을 대신하지 않는다.

## Evidence and limits

- [충돌 비교 실험](../week6/kafka-settings-experiments.md#conflicting-fact-experiment): 실제 Kafka·MySQL·MANUAL_IMMEDIATE에서 두 후보를 비교했다. DLQ 후보의 원문·key·출처 보존, 기존 수량·행 ID 유지, 원본 위치 1→2와 정상 주문 43 처리 뒤 3을 확인했다.
- 비교한 구체 입력은 같은 eventId·orderId의 다른 상품 수량이다. 도메인의 다른 시각·품목·식별자 충돌 보호는 기존 DB 검증을 유지하지만 모든 조합을 이번 Kafka 실험에서 실행한 것은 아니다.
- [정책 반영 기록](../week6/implementation-log.md#conflicting-fact-adoption). 채택 문서화에서 테스트를 새로 실행한 것으로 기록하지 않는다. 실제 업무용 오류 처리기 연결 후 추가 검증한다.
