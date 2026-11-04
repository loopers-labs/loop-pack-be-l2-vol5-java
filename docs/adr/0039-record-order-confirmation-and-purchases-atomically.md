# ADR 0039: 완료 이벤트 기록과 구매 활동을 함께 저장하고 동일 사실의 재전달을 no-op 처리한다

Status: Accepted
Related: [ADR 0038](0038-store-order-purchase-activities-in-one-db-transaction.md), [week6 설계](../week6/design.md#purchase-activity-storage)

Follow-up: 자동 구독 없이 수신 파싱·DB 연결을 준비하는 범위는 [ADR 0040](0040-prepare-order-event-receiving-without-auto-subscription.md)에서 확장한다. 이 ADR의 DB 원자성·동일 사실 no-op 결정은 Accepted를 유지한다. 아래 본문은 당시 구현 범위와 맥락을 보존한다.

Follow-up (conflict): [ADR 0047](0047-quarantine-conflicting-order-confirmation-facts.md)에서 같은 식별자의 다른 사실을 DLQ로 분리한다. 기존 DB 보호·오류 분기 결정은 Accepted를 유지하며 아래 본문은 당시 기록으로 보존한다.

## Context

ADR 0038은 한 주문의 상품별 구매 활동을 같은 DB 트랜잭션으로 저장하고 orderId·productId 조합의 중복을 막는다. 당시에는 중복 insert가 오류로 전파됐고 메시지 단위의 처리 완료 여부는 기록하지 않았다.

사용자는 처리 완료 기록이 Kafka offset과 다른 MySQL의 업무 완료 기록임을 확인했고, 모든 구매 활동과 같은 트랜잭션에 저장하기로 했다. 같은 이벤트의 동일 사실을 다시 받으면 추가 효과 없이 정상 종료해야 한다. 과제 계약에 따라 eventId·orderId의 충돌이나 수량·시각·품목 변경을 같은 사실로 숨기지 않는다.

## Decision

- `OrderPurchase`에 메시지의 eventId를 추가한다. 기존 Kafka JSON과 producer의 고정 eventId 생성 방식은 변경하지 않는다. 저장 입력은 eventId·orderId·confirmedAt·정규화된 상품별 수량을 보존한다.
- `processed_order_confirmation`에 eventId·orderId와 비교용 `fact_snapshot`을 저장한다. eventId와 orderId 각각에 DB 유일 제약을 둔다. 활동 테이블의 `(order_id, product_id)` 유일 제약도 유지한다.
- `fact_snapshot`은 `orderId|Instant|productId:quantity,...` 형식이다. 양수 ID·수량과 정규화된 품목을 사용해 상품별 수량을 합산·정렬하며, 입력 배열 순서와 무관하게 같은 사실을 비교한다. 이 문자열은 내부 비교 표현으로 Kafka wire 계약이 아니다. 본문을 복원하지 않아도 비교할 수 있게 longtext에 보존한다.
- domain의 `ProcessedOrderConfirmation.verifySameFact()`가 eventId·orderId와 snapshot의 일치를 판단한다. 불일치는 `OrderConfirmationConflictException`으로 드러낸다.
- application의 `PurchaseActivityRecorder.record()`가 새 DB 트랜잭션에서 완료 기록과 모든 품목을 저장한다. 기존 완료 사실을 찾았으면 대조 후 no-op한다. 완료 기록을 먼저 insert해도 모든 품목과 함께 commit돼야 외부에서 완료로 관찰된다. 중간 실패는 완료 기록·앞서 저장한 활동을 모두 rollback한다.
- application의 `OrderConfirmationHandler`가 트랜잭션 호출의 완료·예외를 받는다. infrastructure가 완료 테이블의 두 식별자 제약 위반만 `DuplicateOrderConfirmationException`으로 변환한다. MySQL 중복 오류 1062와 알려진 제약 이름을 함께 확인하며 다른 DB 오류는 그대로 전파한다.
- 동시 중복에서 실패한 쓰기 트랜잭션이 rollback된 뒤, 별도의 `REQUIRES_NEW` 읽기 트랜잭션에서 승자의 사실을 재조회한다. 동일 사실이면 정상 no-op, 다른 사실이면 충돌, 완료 기록을 찾지 못하면 원래 경쟁 예외를 전파한다. 실패한 트랜잭션 안에서 예외를 삼키지 않는다.
- 완료 기록은 MySQL의 업무 반영 기록이며 Kafka committed offset이 아니다. 이번 단계에는 Kafka listener·ack·offset commit을 연결하지 않는다. 실제 listener는 후속 단계에서 handler의 commit 또는 검증된 no-op 이후에 ack해야 한다.

## Options considered

| 방식 | 판단 |
| --- | --- |
| 구매 활동의 중복 insert를 오류로만 반환 | 추가 저장은 막지만 정상 재전달을 업무 실패로 취급함 |
| 완료 기록을 활동과 별도 commit | 일부 활동 누락 또는 완료 여부와 활동 결과의 불일치가 생길 수 있음 |
| 먼저 조회한 결과만 신뢰 | 동시에 조회하면 둘 다 미처리로 판단할 수 있음 |
| 모든 DB 오류를 중복으로 무시 | 업무 실패·충돌을 정상 완료로 숨길 수 있음 |
| 같은 TX 저장 + DB 유일성 + rollback 뒤 동일 사실 대조 — 채택 | 원자성과 정상 재전달을 함께 다루고 동시 경쟁·충돌·다른 오류를 구분함 |

## Consequences

동일 주문 확정 사실을 반복 처리해도 완료 기록 1행과 상품별 활동만 유지한다. 두 호출이 동시에 들어와도 동일 사실이면 둘 다 정상 종료할 수 있다. 다른 사실은 오류로 전파하며 기존 완료 사실·구매 효과를 변경하지 않는다.

도메인은 사실의 검증·대조를, application은 트랜잭션과 경쟁 복구 경계를, infrastructure는 실제 유일 제약과 DB 오류 변환을 담당한다. 쓰기와 실패 후 확인은 각각 새 트랜잭션이므로 동일 호출의 실패한 영속성 컨텍스트·조회 snapshot을 재사용하지 않는다.

이 결정만으로 실제 Kafka 재전달·offset commit 순서·broker 장애를 검증한 것은 아니다. 완료 이벤트와 활동의 보존·정리는 앞으로 같은 사실의 재처리 보장 범위와 함께 다뤄야 한다.

## Evidence

- [저장 입력과 정규화](../../apps/commerce-streamer/src/main/java/com/loopers/domain/purchase/OrderPurchase.java)
- [완료 사실 모델](../../apps/commerce-streamer/src/main/java/com/loopers/domain/purchase/ProcessedOrderConfirmation.java)
- [처리 진입점](../../apps/commerce-streamer/src/main/java/com/loopers/application/purchase/OrderConfirmationHandler.java), [트랜잭션 경계](../../apps/commerce-streamer/src/main/java/com/loopers/application/purchase/PurchaseActivityRecorder.java)
- [MySQL 오류 변환](../../apps/commerce-streamer/src/main/java/com/loopers/infrastructure/purchase/ProcessedOrderConfirmationRepositoryImpl.java)
- [실제 MySQL 검증](../../apps/commerce-streamer/src/test/java/com/loopers/application/purchase/PurchaseActivityRecorderTest.java), [오류 변환 검증](../../apps/commerce-streamer/src/test/java/com/loopers/infrastructure/purchase/ProcessedOrderConfirmationRepositoryTest.java)
- 실행 결과와 현재 검증 범위는 [week6 구현 기록](../week6/implementation-log.md#idempotent-purchase-handling)을 따른다.
