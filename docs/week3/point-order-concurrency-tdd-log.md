# W3 포인트 10000원에 대한 주문 3개 경쟁 검증

[전체 설계](../week2/commerce-erd-draft.md) · [정책 선택](../week2/commerce-policy-decisions.md) · [TDD 계획](../week2/commerce-tdd-plan.md) · [완료 체크리스트](../week2/commerce-completion-checklist.md)

한 사용자의 잔액 10,000원을 서로 다른 4,000원 주문 3개가 동시에 사용하려 할 때, 2개만 확정되고 거절된 주문의 재고·결제 결과가 보존되는지 확인한다. 실제 서비스의 트랜잭션과 비관적 잠금을 유지하고 테스트를 먼저 추가했다.

기준 ID는 `W3-POINT-RACE-01`이다. 출처는 사용자가 제공한 W3 Implementation Quest의 「4. 실패·경쟁 결과 확인」 포인트 경쟁 항목이다. 문서와 과제의 기대값을 대조한 뒤 신규 테스트를 추가했으며, 단독·관련 회귀·전체 검사에서 통과했다. 최신 전체 검사는 59개 스위트·548개 통과다. 실제 서비스·잠금 정책은 변경하지 않았다.

## 확인한 계약과 테스트 준비

| 구분 | 확인한 내용과 적용 |
| --- | --- |
| 과제의 필수 조건 | 한 사용자 잔액 10,000원, 서로 다른 4,000원 DRAFT 주문 3개, 충분한 재고 |
| 필수 기대값 | 확정 2·잔액 부족 1·기술 오류 0·최종 잔액 2,000원, 거절 주문의 재고 유지 |
| 기존 업무 계약 | C10에서 최초 확정은 주문·재고·포인트·결제 결과를 함께 저장한다. 포인트 부족은 `INSUFFICIENT_POINTS`이며 실패 주문은 DRAFT를 유지한다 |
| 기존 잠금 계약 | P09·P13의 READ_COMMITTED, 주문 → 사용자 → 브랜드 ID 오름차순 → 상품 ID 오름차순. DB 잠금 타임아웃 3초·자동 재시도 없음 유지 |
| 테스트의 선택 | alice의 주문마다 서로 다른 브랜드·상품을 준비한다. 상품별 단가 4,000원·재고 5·구매 수량 1. 재고 부족이 잔액 부족을 가리지 않으며 공통 브랜드·상품 잠금에 의한 직렬화를 피하는 fixture |
| 무관한 데이터 | bob의 잔액 7,000원·별도 브랜드/상품·경쟁하지 않는 DRAFT 주문을 준비해 영향 없음을 비교 |

브랜드·상품 분리는 실험용 데이터 구성이지 새 업무 정책이 아니다. 각 주문이 바꾸는 업무 행 중 공통 대상은 alice의 사용자 행이며, 성공할 주문 ID나 실행 순서는 지정하지 않는다. HTTP 오류 계약은 기존 API 문서를 따르되 이번 경쟁 테스트는 HTTP가 아닌 실제 서비스 호출을 검증한다.

## 결과와 저장 상태의 연결

| 확인 대상 | 기대값 |
| --- | --- |
| 결과 분류 | 서비스 프록시가 커밋 후 정상 반환한 성공 2, 정확한 `PointsException / INSUFFICIENT_POINTS` 거절 1. SQL·타임아웃·재고 부족 등 다른 오류는 0 |
| 집계식 | 성공 2 + 업무 거절 1 + 기술 오류 0 = 요청 3 |
| 성공 주문 | 결과의 주문 ID와 DB CONFIRMED ID 집합 일치. 결제액 4,000원·확정 시각 존재, 품목·원래 주문 금액 보존 |
| 거절 주문 | DRAFT 전체 행·결제액 없음·확정 시각 없음 유지. 해당 상품의 재고 5와 전체 행 보존 |
| 상품별 수량식 | 초기 재고 5 − 해당 상품의 성공 주문 수량 = 최종 재고. 성공한 두 상품은 각각 4, 거절 상품은 5 |
| 잔액식 | 초기 잔액 10,000 − DB 성공 주문의 결제액 합 8,000 = 최종 잔액 2,000 |
| 다른 데이터 | 주문항목 스냅샷과 행 수·ID, 성공에 필요한 컬럼 외 값, bob·다른 브랜드/상품/주문 보존 |

최종 잔액이 음수가 아니라는 조건만으로 통과시키지 않는다. 요청 결과를 주문 ID별 DB 상태와 연결하여 전부 거절하거나, 결제 없이 확정하거나, 실패 주문의 재고를 차감하는 오류를 함께 확인한다.

## 실행 경계와 자원 정리

fixture는 worker 시작 전에 커밋한다. 부모 테스트 트랜잭션·worker를 감싸는 외부 트랜잭션을 두지 않고 주입된 실제 `OrderService` 프록시를 호출한다. 각 worker의 호출 전후에 트랜잭션이 남아 있지 않음을 검사하고, 성공·업무 거절·예상 밖 오류를 요청별로 수집한다.

worker 3개가 준비되면 시작 신호만 함께 보낸다. 서비스 내부에는 장벽·sleep·spy로 실행 순서를 강제하지 않는다. 준비·시작 대기는 5초, 각 Future 완료·executor 종료 대기는 10초로 제한한다. finally에서 시작 해제·Future 취소·executor 종료를 수행하고, 모든 worker가 종료된 뒤 DB를 다시 읽고 정리한다. 종료가 확인되지 않으면 DB 정리를 차단한다.

## 변경 범위와 검증 순서

| 대상 | 이번 책임 |
| --- | --- |
| [OrderPointConcurrencyIntegrationTest](../../apps/commerce-api/src/test/java/com/loopers/application/order/OrderPointConcurrencyIntegrationTest.java) | 포인트 경쟁 3개 요청과 주문별 재고·결제·잔액의 실제 DB 검증 추가 |
| 서비스·저장소·스키마·API | 기존 구현으로 통과하여 변경하지 않음 |
| 설계·정책·TDD 계획·체크리스트 | 포인트 경쟁의 실제 완료 증거를 추가하고 과거 547개 검증 기록은 보존 |

신규 테스트 → 기존 주문·포인트 관련 회귀 → 전체 `:apps:commerce-api:check` 순서로 실행한다. 이미 구현된 기능이 첫 실행부터 통과하면 업무 Red를 만들지 않는다. 결함이 발견되면 기대값을 완화하지 않고 원인을 확인해 필요한 코드만 수정한다.

이번 검증으로 지정 금액의 충전·결제 경쟁, HTTP 동시 호출, 모든 잠금 대기 순서나 장애 상황까지 완료 처리하지 않는다. 지정 충전·결제 경쟁은 이후 [별도 테스트](point-charge-order-concurrency-tdd-log.md)로 검증했다. 마지막 증분의 전체 최종 검사·제출 정리는 사용자 요청으로 보류하며, 기술 글은 사용자가 작성한다.

## 실행 기록

### 테스트 우선 검증

| 단계 | 실제 수행과 결과 |
| --- | --- |
| 문서 확인 | 과제 원문·전체 설계·C10 오류 계약·P09/P13·기존 TDD 기대값 대조. 잔액/재고 부족·기술 오류를 구분하고 새 제품 정책은 추가하지 않음 |
| 테스트 추가 | alice 잔액 10,000, 독립 브랜드·상품 3개와 각 4,000원 DRAFT, bob 잔액 7,000·별도 DRAFT 준비. 실제 서비스의 시작 경합 후 DB 결과 검증 |
| 첫 실행 | 2026-10-08 21:08 KST, 신규 테스트 1개·Checkstyle test 통과. 처음부터 Green이며 업무 Red를 관찰한 것으로 기록하지 않음 |
| 수정 판단 | 기존 구현이 합의한 기대값을 충족하여 생산 코드·트랜잭션·락 변경 없음. 기대값을 완화하거나 인위적인 Red를 만들지 않음 |
| 구조 정리 | 요청과 결과를 주문 ID에 연결하고 실행·정리와 DB 보존 검증을 helper로 분리. 기존 테스트 공통화나 범위 밖 리팩터링은 하지 않음 |

### 확인한 결과

| 확인 대상 | 통과한 assertion |
| --- | --- |
| 독립 대상·실행 | 주문·브랜드·상품 ID 각각 3개 고유, DRAFT·단가/총액 4,000·수량 1·재고 5. 서비스 프록시 사용과 호출 전후 부모 트랜잭션 없음, worker 준비·종료 확인 |
| 결과 집계 | 성공 2·정확한 PointsException/INSUFFICIENT_POINTS 1·기술/예상 밖 오류 0, 합계 3 |
| 주문별 결과 | 성공 ID 집합=DB CONFIRMED ID 집합, 결제액 각 4,000·확정 시각 존재·반환값과 재조회 동일. 실패 DRAFT 전체 행·조회 결과 유지 |
| 상품별 수량 | 성공 상품은 각 5−1=4, 거절 상품은 5−0=5이고 전체 행 보존. DB 성공 품목 수량 합 2 |
| 잔액 | DB 성공 결제액 합 8,000, 초기 10,000−8,000=최종 2,000. bob의 잔액 7,000과 전체 행 불변 |
| 보존 | 6개 테이블 ID·행 수·품목 스냅샷 보존. 성공 주문/상품·alice의 허용 변경 외 값과 무관 데이터 보존 |

worker 미종료 시 정리를 차단하는 경로는 마련했지만 별도 장애 주입으로 검증한 것은 아니다. 브랜드·상품의 공유 잠금을 피하는 fixture로 구성했으며, 사용자 잠금을 제거한 비교 실험이나 모든 DB 대기 순서를 관찰했다는 뜻은 아니다.

### 실행 명령과 검사 결과

```bash
./gradlew :apps:commerce-api:test --tests '*OrderPointConcurrencyIntegrationTest' :apps:commerce-api:checkstyleTest --console=plain -q

./gradlew :apps:commerce-api:test --tests '*OrderPointConcurrencyIntegrationTest' --tests '*OrderStockConcurrencyIntegrationTest' --tests '*OrderConcurrencyIntegrationTest' --tests '*OrderTransactionTest' --tests '*OrderServiceIntegrationTest' --tests '*StockLostUpdateControlTest' --tests '*PointServiceIntegrationTest' --tests '*UserPointsTest' :apps:commerce-api:checkstyleTest --console=plain -q

./gradlew :apps:commerce-api:check --console=plain -q
```

| 실행 시점 (2026-10-08 KST) | 검사 | 실제 결과 |
| --- | --- | --- |
| 21:08 | 신규 테스트·Checkstyle test | 1개 통과, 종료 코드 0 |
| 21:09 | 관련 주문·포인트 회귀·Checkstyle test | 8개 스위트·48개 통과, 실패·오류·건너뜀 0, 종료 코드 0. 신규 1·재고 경쟁 1·기존 동시성 7·주문 트랜잭션 4·주문 서비스 3·대조군 1·포인트 서비스 18·포인트 도메인 13 |
| 21:14 | 전체 check | 종료 코드 0. API 모듈 XML 59개 스위트·548개 통과, 실패·오류·건너뜀 0. Checkstyle XML 11개 보고서 위반 0·ArchitectureTest 1개 통과 |

전체 548개는 이전 547개에 이번 포인트 경쟁 1개를 추가한 수다. 단독·관련 회귀의 재실행 수를 더하지 않으며, 과거 증분의 검사 수는 당시 증거로 유지한다.
