# W3 재고 5개에 대한 주문 8개 경쟁 검증

[전체 설계](../week2/commerce-erd-draft.md) · [TDD 계획](../week2/commerce-tdd-plan.md) · [완료 체크리스트](../week2/commerce-completion-checklist.md)

재고 5개를 서로 다른 주문 8개가 각각 1개씩 구매할 때, 실제 주문 서비스가 5개만 확정하고 성공한 주문의 재고·포인트만 차감하는지 검증한다. 갱신 유실 대조군과 달리 기존 Spring 서비스·저장소·MySQL의 트랜잭션과 비관적 잠금을 그대로 사용한다.

기준 ID는 `W3-STOCK-RACE-01`이다. 출처는 사용자가 제공한 W3 Implementation Quest의 「4. 실패·경쟁 결과 확인」 재고 경쟁 항목이다. **신규 테스트·관련 회귀·전체 검사 완료**이며, 전체 58개 스위트·547개가 통과했다. 실제 서비스·잠금 정책은 변경하지 않았다.

## 준비 조건과 기대값

| 항목 | 준비 또는 기대값 |
| --- | --- |
| 상품 | 미삭제 상품, 재고 5개, 단가 1,000원. 단가는 테스트 fixture이며 새 가격 정책이 아님 |
| 구매자·주문 | 기존 fixture 고객 alice·bob이 각각 4개씩 생성한 서로 다른 DRAFT 주문 8개. 주문마다 같은 상품 1개, 각 고객 잔액 10,000원 |
| 실행 | 8개 worker의 시작만 맞춰 실제 `OrderService.confirm()` 프록시 호출. 요청별 독립 트랜잭션, 내부 장벽·sleep·재시도 없음 |
| 결과 집계 | 확정 성공 5·정확한 INSUFFICIENT_STOCK 거절 3·기술 오류 0. 다른 업무 오류도 허용하지 않음 |
| 주문별 저장 상태 | 성공한 주문 ID 5개만 CONFIRMED·결제액 1,000원·확정 시각 저장. 거절된 주문 3개는 원래 DRAFT 행 전체 보존 |
| 수량 보존 | 초기 재고 5 − 성공 주문의 DB 품목 수량 합 5 = 최종 재고 0 |
| 잔액 보존 | 각 구매자의 초기 잔액 10,000 − 그 구매자의 성공 주문 결제액 합 = 최종 잔액. 두 구매자의 잔액 합은 15,000원 |
| 무관한 데이터 | 모든 주문항목 스냅샷, 다른 상품·브랜드·사용자 및 경쟁하지 않는 DRAFT 주문 보존 |

어느 구매자의 어떤 주문이 먼저 확정되는지는 고정하지 않는다. 반환 결과의 주문 ID와 DB의 확정 주문 ID를 대조하고 구매자별로 차감액을 계산한다. 최종 재고가 음수가 아니라는 검사나 두 구매자의 잔액 합만으로 끝내지 않는다.

## 트랜잭션과 잠금 경계

테스트 전체나 worker 밖에 부모 트랜잭션을 두지 않는다. fixture 준비 호출이 끝나 커밋된 뒤 worker를 시작한다. 각 worker는 서비스 호출 전후에 트랜잭션이 남아 있지 않음을 확인하며, 서비스 프록시의 정상 반환 뒤에만 성공 결과를 수집한다. 커밋 오류는 성공으로 세지 않는다.

기존 서비스의 READ_COMMITTED와 주문 → 사용자 → 브랜드 ID 오름차순 → 상품 ID 오름차순 잠금 순서를 유지한다. 같은 구매자의 사용자 행과 두 구매자가 공유하는 브랜드·상품 행에서 대기할 수 있으므로, 이 검증을 상품 잠금 하나만의 효과로 해석하지 않는다. 현재 고객 매핑·3초 DB 잠금 타임아웃·서버 자동 재시도 없음 정책은 변경하지 않는다.

8개 스레드 풀에서 모든 worker가 준비됐는지 확인한 뒤 시작 신호를 한 번 보낸다. 준비·시작 대기는 5초, 개별 Future 완료·executor 종료 대기는 10초로 제한한다. 실패해도 finally에서 시작 신호 해제·Future 취소·executor 종료를 수행하고, worker 종료가 확인되지 않으면 DB 정리를 하지 않는다. 실제 잠금 구간에는 관찰용 장벽이나 sleep을 넣지 않는다.

## 변경 범위와 검증 순서

| 대상 | 이번 책임 |
| --- | --- |
| [OrderStockConcurrencyIntegrationTest](../../apps/commerce-api/src/test/java/com/loopers/application/order/OrderStockConcurrencyIntegrationTest.java) | 8개 요청 결과와 커밋 후 DB 상태를 연결하는 새 통합 테스트. 기존 2개 주문 경쟁 테스트는 유지 |
| 실제 서비스·저장소·API·스키마 | 기존 구현으로 통과하여 변경하지 않음 |
| 전체 설계·정책·TDD 계획·체크리스트 | 이번 검증의 범위·증거 연결. 이전 546개 결과는 당시 기록으로 보존 |

새 테스트 → 기존 주문 동시성·트랜잭션·서비스·대조군 회귀 → 전체 `:apps:commerce-api:check` 순서로 실행한다. 첫 실행부터 통과하면 업무 Red를 만들기 위해 잠금이나 기대값을 바꾸지 않는다. HTTP 동시 호출·상품 잠금만의 격리 실험·모든 스케줄링 순서를 검증한 것으로 기록하지 않는다.

포인트 10,000원에 4,000원 주문 3개 경쟁은 이후 [별도 포인트 테스트](point-order-concurrency-tdd-log.md), 지정 충전·결제 경쟁은 [충전/결제 테스트](point-charge-order-concurrency-tdd-log.md)로 검증했다. 마지막 증분의 전체 최종 검사·제출 정리는 사용자 요청으로 보류하며, 기술 글은 사용자가 작성한다.

## 실행 기록

### 테스트 우선 검증

| 단계 | 실제 수행과 결과 |
| --- | --- |
| 준비 | 고객 2명·각 4개 주문, 대상 상품 재고 5·단가 1,000, 각 잔액 10,000을 준비. 다른 브랜드·상품·경쟁하지 않는 주문도 저장 |
| 첫 실행 | 2026-10-08 20:55 KST, 신규 테스트 1개·Checkstyle test 통과. 업무 Red 없이 첫 실행부터 Green |
| 변경 판단 | 기존 서비스가 기대값을 만족하여 생산 코드 수정 없음. 기대값·기존 검사 규칙을 완화하지 않음 |
| 리뷰와 정리 | 요청자·주문 ID·반환값·오류를 묶어 수집하고, DB 검증과 자원 정리를 helper로 분리. 실행 전 assertion 설명의 오기 교정 |

### 확인한 저장 결과

| 확인 대상 | 통과한 assertion |
| --- | --- |
| 준비·실행 경계 | 고유 주문 ID 8개·DRAFT·각 수량 1·총액 1,000. 실제 Spring 프록시 사용, 부모 트랜잭션 없음, 모든 worker 준비 후 시작·종료 확인 |
| 결과 분류 | 프록시 정상 반환 성공 5·정확한 ProductStockException/INSUFFICIENT_STOCK 3·기술/예상 밖 오류 0, 합계 8 |
| 주문별 성공·실패 | 성공 결과의 ID 집합과 DB CONFIRMED ID 집합 동일. 성공한 5개는 결제액 1,000·확정 시각 존재·재조회 결과 동일. 실패한 3개는 DRAFT 전체 행과 응답 조회값 유지 |
| 수량식 | DB의 성공 주문 품목 수량 합 5, `5 − 5 = 0`으로 최종 재고와 일치 |
| 잔액식 | 각 사용자별로 성공 주문의 DB 결제액만 차감. 결제액 합 5,000·잔액 합 15,000. 성공자별 분포는 고정하지 않음 |
| 보존 | 6개 테이블의 ID·행 수 보존, 주문항목 전체 불변. 성공 주문의 상태·결제·확정/갱신 시각, 대상 재고/갱신 시각, 구매자 잔액/갱신 시각 외 컬럼·무관한 행 보존 |

worker 미종료 시 정리를 막는 경로는 코드에 포함했지만 미종료·네트워크 단절을 강제 주입한 별도 장애 테스트는 아니다. 8개 요청의 동시 출발을 검증했으며, DB에서 모두 같은 순간에 잠금을 기다렸거나 모든 실행 순서를 관찰했다는 뜻은 아니다.

### 실행 명령과 검사 결과

```bash
./gradlew :apps:commerce-api:test --tests '*OrderStockConcurrencyIntegrationTest' :apps:commerce-api:checkstyleTest --console=plain -q

./gradlew :apps:commerce-api:test --tests '*OrderStockConcurrencyIntegrationTest' --tests '*OrderConcurrencyIntegrationTest' --tests '*OrderTransactionTest' --tests '*OrderServiceIntegrationTest' --tests '*StockLostUpdateControlTest' :apps:commerce-api:checkstyleTest --console=plain -q

./gradlew :apps:commerce-api:check --console=plain -q
```

| 실행 시점 (2026-10-08 KST) | 검사 | 실제 결과 |
| --- | --- | --- |
| 20:55 | 신규 테스트·Checkstyle test | 1개 통과, 종료 코드 0 |
| 20:56 | 관련 주문 테스트·Checkstyle test | 5개 스위트·16개 통과, 실패·오류·건너뜀 0, 종료 코드 0. 신규 1·기존 동시성 7·주문 트랜잭션 4·주문 서비스 3·대조군 1 |
| 20:58 | 전체 `:apps:commerce-api:check` | 종료 코드 0. XML 58개 스위트·547개 통과, 실패·오류·건너뜀 0. 기존 57개·546개 대비 새 사례 1개 증가 |
| 20:58 | 전체 검사에 연결된 정적 검사 | Checkstyle XML 11개 보고서 위반 0, ArchitectureTest 1개 통과. 규칙 삭제·완화 없음 |

같은 재고 경쟁 테스트를 단독·관련 회귀·전체 검사에서 각각 실행했다. 서로 다른 테스트 3개로 세지 않으며, 이전 546개 통과 수치는 대조군 증분의 기록으로 보존한다.
