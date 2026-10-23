# ADR 0033: 10분이 지난 미완료 결제 시도는 배치가 reconcile과 같은 규칙으로 마무리한다

Status: Accepted. 확실한 미발송의 즉시 실패와 조회 0건 유지의 근거는 [ADR 0034](0034-close-only-proven-unsent-payments-and-keep-unresolved-pending.md)에 둔다.
Related: [ADR 0028](0028-test-pg-http-boundary-and-no-create-retry.md), [ADR 0031](0031-pg-lookup-retry-circuit-breaker-and-fallback.md), [ADR 0032](0032-persist-payment-result-before-order-application.md)

## Context

미완료 결제 시도를 마무리하는 계기는 고객 reconcile, PG callback, 같은 키 confirm뿐이었다. callback이 오지 않고 고객도 결제 확인을 누르지 않으면 REQUESTED·PENDING·UNKNOWN 시도와, TX B 뒤 TX C 전에 멈춘 주문이 계속 남는다. 그동안 예약이 재고를 잡고 있어 다른 고객이 살 수 없다.

## Decision

**대상**: 주문이 PAYMENT_PENDING이고 결제 시도를 만든 지 10분이 지난 것. 결과를 모르는 시도와, 결제는 종결됐지만 주문에 반영되지 않은 시도가 모두 이 조건 하나로 잡힌다. 기준은 결제 시도의 생성 시각이다. 조회할 때마다 바뀌지 않아 기준이 흔들리지 않는다.

**처리**: `PaymentRecoveryBatch`가 결제 시도 id 오름차순으로 50건씩 끝까지 읽고, 건마다 `OrderFacade.recover(orderId)`를 부른다. `recover`는 reconcile·callback과 같은 `resolve` 규칙을 쓴다.

| 저장 상태 | 처리 |
| --- | --- |
| 결제·주문 반영까지 완료 | 대상에서 빠짐 |
| 결제 SUCCESS·FAILED, 주문 미반영 | PG 조회 없이 TX C |
| 결과를 모름 | 저장된 transactionKey(없으면 providerOrderId)로 조회 → TX B(이력 경로 `BATCH`) → 종결이면 TX C |
| 조회로 확인하지 못함(retry 소진·회로 OPEN) | 아무것도 바꾸지 않고 다음 건 |

- 한 건의 예외는 로그로 남기고 다음 건을 계속한다. 다음 실행에서 다시 대상이 된다.
- 기준 시각은 실행 시작 때 고정한다.
- 조회는 고객 reconcile과 같은 `ResilientPaymentProvider`를 거친다. PG 조회 장애가 계속되면 회로가 열려 이후 건은 GET 없이 넘어간다.

**하지 않는 것**: 결제 생성 POST를 다시 보내지 않는다(ADR 0028). 10분·30분 혹은 더 오래 지났거나 조회가 0건이라는 이유로 FAILED 처리하거나 예약을 풀지 않는다. 시간과 조회 0건은 최종 실패의 증거가 아니다. ADR 0034에서 잠시 도입한 자동 만료도 이 이유로 철회했다.

**실행**: `PaymentRecoveryScheduler`가 `fixedDelay` 1분으로 실행한다. 앞 실행이 끝난 뒤 1분을 기다리므로 같은 서버 안에서 실행이 겹치지 않는다. 포인트 만료 배치와 같이 `payment.recovery.scheduling-enabled=true`로 켜며 기본은 꺼져 있다. 테스트는 배치를 직접 호출한다.

## Alternatives and costs

- **기준을 마지막 변경 시각으로**: PENDING 응답을 다시 받아도 시도 상태가 바뀌지 않으면 변경 시각도 그대로라 차이가 작다. 생성 시각이 더 단순하다.
- **한 번에 정해진 개수만 처리**: PG 부하는 줄지만, 계속 PENDING인 오래된 시도가 앞자리를 차지하면 뒤 시도가 처리되지 않는다. 끝까지 처리하고, PG 부하는 기존 회로에 맡겼다.
- **오래 모르는 시도를 실패 처리**: 재고는 풀리지만 PG에서 성공했거나 나중에 거래가 생길 수 있다. 조회 0건만으로도 이를 부정할 수 없고 취소 계약도 없어 택하지 않았다(ADR 0034).
- **한계**: 오래 PENDING·UNKNOWN인 시도는 매 실행마다 다시 조회된다. 조회 횟수 제한·장기 미확인 알림·수동 처리 표시는 없다. 실행 상태는 메모리에 없고 DB 조건으로 다시 찾으므로 재시작에 영향받지 않지만, 여러 서버에서 켜면 같은 시도를 동시에 처리할 수 있다. 이때도 TX B·C는 주문 잠금 안에서 한 번만 반영된다. 10분·1분·50건은 학습값이다.

## 검증

`PaymentRecoveryBatchTest`(실제 MySQL·가짜 PG): 9분 된 시도는 GET 0, 11분 된 PENDING은 조회로 CONFIRMED·이력 `BATCH`·POST 추가 0, PG 0건인 REQUESTED는 예약 유지·POST 0, 저장된 FAILED는 GET 0으로 재고 반환, 503이 계속된 건은 그대로 두고 다음 건 확정, 회로 OPEN은 GET 0, 완료된 주문은 대상 아님, 한 건 예외에도 나머지 처리·다음 실행에서 복구, 51건으로 페이지 경계·반복 실행 시 추가 효과 없음. `PaymentChaosTest`: PG 죽음·느려짐·DB 멈춤 뒤 배치가 PG 결과대로 주문을 마무리하고, 시드 고정 장애를 섞은 callback·reconcile·배치·같은 키 confirm 동시 실행 뒤에도 주문당 POST 1·재고 불변식이 유지됨. 연결 단계에서 거부돼 PG에 거래가 없는 시도는 배치로도 끝나지 않는 것을 확인했고, 이 한계는 ADR 0034에서 다뤘다. 실행 범위는 [week5 구현 기록](../week5/implementation-log.md#recovery-batch)과 [카오스 테스트](../week5/implementation-log.md#chaos)에 둔다.
