# R09. 주문 확정 파사드 전환

[전체 요구사항](../total_requirement.md) · 작업 브랜치: `volume-3/r09-order-confirm-facade` · PR 대상: `volume-3/main`

상태: 구현·검증 완료([결과](result.md)). 최종 check 271건 통과. 브랜치는 R08 브랜치(PR #20)에서 분기했고 R08 병합(PR #20) 후 main 기준으로 리베이스했다. [PR #21](https://github.com/corinB/loop-pack-be-l2-vol5-java/pull/21) 리뷰 중.
기준 자료: 2026-10-09 사용자 제안 다이어그램(`OrderController → ConfirmOrderUseCase ← ConfirmOrderFacade → OrderService / ProductService → 각 Repository`).

## 1. 목적

주문 확정을 파사드가 컨텍스트별 Service(주문·상품·지갑)를 순서대로 호출하는 구조로 바꾼다. 사용자가 정한 목적은 세 가지다.

- **서비스별 책임 분리:** 각 Service가 자기 컨텍스트의 저장소와 규칙만 다룬다.
- **`ConfirmOrderWriter` 제거:** 인프라 구현체가 저장소 4개를 묶어 조회·저장하던 포트를 없앤다.
- **Service 재사용:** 재고 차감·포인트 결제 같은 동작을 다른 유스케이스에서도 Service 메서드로 다시 쓸 수 있게 한다.

## 2. 현재 동작과 변경점

| 구분 | 현재 | 변경 |
|---|---|---|
| 진입점 | `ConfirmOrderService implements ConfirmOrderUseCase` | `application.ordering.facade.ConfirmOrderFacade implements ConfirmOrderUseCase` |
| 잠금 조회·저장 | `ConfirmOrderWriter` 포트, 구현 `JpaConfirmOrderWriter`(주문 → 지갑 → 상품 id 오름차순) | 파사드가 `OrderService` → `WalletService` → `ProductService` 순서로 호출 |
| 업무 규칙 | 도메인 `OrderConfirmationPolicy`가 전부 검증한 뒤 결제·주문 변경 | 각 Service가 자기 검증·변경. 오류 우선순위는 호출 순서로 유지 |
| 수량 합산 | 정책 내부 `aggregateQuantities` | 도메인 `Order.quantitiesByProductId()` |

## 3. 포함·제외 범위

**포함**: 파사드, Service 메서드 추가, `Order.quantitiesByProductId()`, `ConfirmOrderService`·`ConfirmOrderWriter`·`ConfirmOrderLoad`·`JpaConfirmOrderWriter`·`OrderConfirmationPolicy`·`OrderConfirmation` 제거, 관련 테스트 재배치, 문서.

**제외**: 주문 확정 API 계약 변경, 잠금 방식(비관적 잠금) 변경, 브랜드 삭제 흐름(R07 구조 그대로), 다른 유스케이스의 파사드 전환.

## 4. 규칙

- 잠금 순서는 주문 → 지갑 → 상품 id 오름차순을 유지한다(R02·R04·R07의 전제).
- 오류 우선순위는 주문 상태 → 상품(품목 등장 순서로 삭제·재고) → 잔액을 유지한다.
- 확정 전체는 파사드의 한 트랜잭션이다. 중간 단계가 실패하면 앞 단계의 변경(재고 차감 등)도 롤백된다.
- 저장 순서는 상품 → 지갑 → 사용 기록 → 주문(주문 기록 cascade)을 유지한다.

## 5. 시나리오와 기대 결과

기존 `ConfirmOrderIntegrationTest`·`ConfirmOrderSqlRollbackIntegrationTest`·`ConfirmOrderConcurrencyIntegrationTest`·`OrderApiE2ETest`의 확정 시나리오가 기대값 변경 없이 통과한다. 특히 다음을 유지한다.

| 시나리오 | 기대 결과 |
|---|---|
| 정상 확정 | 재고·잔액 차감, USE·PAID 기록, CONFIRMED |
| 두 번째 품목 재고 부족 | 전체 롤백 |
| 포인트 부족 | 재고 차감을 포함해 전체 롤백 |
| 삭제된 상품·이미 확정된 주문 | 기존 오류로 거절, 재고·잔액·기록 보존 |
| 동시 확정 | R02 동시성 결과 유지 |

## 6. 완료 조건

- `ConfirmOrderWriter`·`OrderConfirmationPolicy`가 운영 코드에서 사라진다.
- 위 테스트가 기대값 변경 없이 통과하고 `./gradlew :apps:commerce-api:check`가 통과한다. Checkstyle·ArchUnit을 완화하지 않는다.

## 7. 미정 사항

모두 [트레이드오프](trade_off/total_trade_off.md)에서 결정했다.
