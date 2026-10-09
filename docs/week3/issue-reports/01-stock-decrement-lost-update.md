# 재고 차감 시 갱신 유실 재현

상태: 대조군 재현 완료 · 실제 서비스에 Stock 비관적 배타락을 적용해 해결 및 검증 완료

## 재현

MySQL에 재고 5를 저장하고 두 독립 트랜잭션이 잠금 없는 SELECT로 각각 5를 읽는다. 두 읽기가 끝난 뒤 각 트랜잭션이 읽어 둔 값에서 1을 뺀 **상수 4**를 저장하고 commit한다.

현상 재현 테스트: [StockLostUpdateControlTest](../../../apps/commerce-api/src/test/java/com/loopers/product/infrastructure/StockLostUpdateControlTest.java)

```bash
./gradlew :apps:commerce-api:test --tests '*StockLostUpdateControlTest'
```

## 결과


| 결과          | 정상 처리 | 대조군 관찰 |
| ----------- | -----: | ------: |
| 두 트랜잭션의 조회값 | 5·5   | 5·5    |
| 두 차감 후 재고   | 3개    | 4개     |


두 트랜잭션 모두 완료됐지만 `초기 재고 5 − 차감 2 = 최종 재고 3`이 성립하지 않는다. 대조군 테스트는 재고 4와 불변식 위반을 확인해 통과한다.

## 원인

Lost Update(갱신 유실). 두 트랜잭션이 같은 재고 5를 읽고 각각 4를 저장해 차감 한 번이 반영되지 않았다.

## 해결 방법

주문 확정의 재고 조회에 `PESSIMISTIC_WRITE`를 적용했다. 같은 Stock 행을 변경하는 요청은 앞선 트랜잭션이 종료될 때까지 기다리고, 이후 최신 수량을 읽어 검증·차감한다. 조회부터 변경까지 배타락을 유지하므로 두 요청이 같은 이전 수량으로 덮어쓰는 갱신 유실을 막는다.

관리자의 최종 재고 수량 변경에도 같은 Stock 배타락을 적용해 주문 차감의 보호 규칙을 우회하지 않도록 했다. Product는 공유락으로 수정·삭제를 막고, 수량 변경의 경합은 Stock 행에서 처리한다. 재고·포인트·주문 확정 결과는 같은 트랜잭션에서 commit·rollback한다.

적용 코드: [StockJpaRepository](../../../apps/commerce-api/src/main/java/com/loopers/product/infrastructure/StockJpaRepository.java), [OrderUseCase](../../../apps/commerce-api/src/main/java/com/loopers/order/application/OrderUseCase.java), [ProductUseCase](../../../apps/commerce-api/src/main/java/com/loopers/product/application/ProductUseCase.java)

## 해결 후 검증

[OrderConcurrencyTest](../../../apps/commerce-api/src/test/java/com/loopers/order/application/OrderConcurrencyTest.java)에서 실제 MySQL과 독립 트랜잭션으로 재고 5개에 서로 다른 주문 8건을 동시에 확정했다.

- 성공 5건 · 재고 부족 3건 · 기술 오류 0건 · 최종 재고 0개
- `초기 재고 5 − 성공 주문의 수량 합 5 = 최종 재고 0` 확인
- 성공 주문의 확정·결제액·포인트 차감을 확인하고, 거절된 주문은 DRAFT·미결제·기존 잔액을 유지하는지 검증

2026-10-09 전체 `./gradlew :apps:commerce-api:check` 실행에서 해당 경쟁 테스트와 갱신 유실 대조군 모두 통과했다. 대조군은 잠금 없는 방식의 문제를 보여주는 재현용으로 유지했으며, 제품 코드에 락을 적용한 검증과 구분한다. 상세 결과는 [동시성 테스트 결과](../concurrency-test-results.md)에 정리했다.
