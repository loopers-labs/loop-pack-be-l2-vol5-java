# W3 포인트 충전과 주문 결제의 동시 실행 검증

[전체 설계](../week2/commerce-erd-draft.md) · [API 계약](../week2/commerce-api-contract.md) · [정책 선택](../week2/commerce-policy-decisions.md) · [TDD 계획](../week2/commerce-tdd-plan.md) · [완료 체크리스트](../week2/commerce-completion-checklist.md)

잔액 10,000원인 한 사용자가 2,000원 충전과 7,000원 주문 확정을 동시에 요청해도 두 변경이 모두 반영되는지 검증한다. 기준 ID는 `W3-CHARGE-ORDER-01`이며 사용자가 제공한 W3 Implementation Quest 「4. 실패·경쟁 결과 확인」의 충전과 결제 항목을 따른다.

문서·호출 경로를 확인하고 신규 테스트를 먼저 추가했으며 첫 실행부터 통과했다. 생산 코드·트랜잭션·락 변경은 없다. 신규 사례를 포함한 관련 테스트·ArchUnit 50개와 Checkstyle test를 확인했다. **사용자 요청으로 전체 최종 검사와 제출 정리는 보류하며, 제출용 기술 글은 사용자가 별도로 작성한다.** 이 기록은 기술 글을 대신하지 않는다.

## 확인한 계약과 테스트 준비

| 구분 | 준비·기대값 |
| --- | --- |
| 필수 장면 | alice 잔액 10,000원, 충전 2,000원과 총액 7,000원 DRAFT의 최초 확정을 함께 실행 |
| 주문 구성 | 상품 A 단가 2,000원·수량 2·재고 5, 상품 B 단가 3,000원·수량 1·재고 4. 재고 부족이 잔액 동시 변경을 가리지 않게 준비 |
| 요청 결과 | 충전 성공 1 + 주문 확정 성공 1, 업무 거절 0·기술 오류 0, 집계 합 2 |
| 잔액 보존식 | 초기 10,000 + 성공 충전액 2,000 − DB 성공 결제액 7,000 = 최종 5,000 |
| 주문·재고 | 같은 주문 ID의 CONFIRMED·결제액 7,000·확정 시각 저장. DB 품목 수량만큼 차감하여 상품 A=3, B=3 |
| 보존 | 품목 스냅샷·행 ID/수·허용한 변경 외 컬럼·bob의 별도 잔액과 주문/상품/브랜드 유지 |

상품 구성과 bob의 무관한 데이터는 테스트용 선택이며 새로운 제품 정책이 아니다. 초기 데이터는 worker 실행 전에 저장을 완료한다. 외부 결제 연동이나 충전 이력 테이블은 추가하지 않는다.

## 트랜잭션과 잠금 경계

| 경로 | 기존 구현에서 확인한 책임 |
| --- | --- |
| `PointService.charge()` 프록시 → `UserRepository.lockById()` | 독립 READ_COMMITTED 트랜잭션에서 사용자 행을 PESSIMISTIC_WRITE로 조회하고 `User.charge()` 후 변경 감지로 저장 |
| `OrderService.confirm()` 프록시 → 주문·사용자·브랜드·상품 저장소 | 주문 → 사용자 → 브랜드 ID 오름차순 → 상품 ID 오름차순으로 잠근 뒤 검증하고 재고·잔액·확정 결과를 한 트랜잭션에 저장 |
| 공유하는 보호 대상 | 두 경로 모두 `UserRepositoryImpl` → `UserJpaRepository.lockById()`로 같은 alice 행을 잠근다. 충전 경로는 주문이나 상품 잠금을 추가로 요구하지 않아 순서를 역전하지 않음 |
| 성공 판정 | 서비스 본문이 아니라 Spring 프록시가 커밋까지 마치고 정상 반환한 호출만 성공으로 집계. 오류를 성공이나 잔액 부족으로 숨기지 않음 |

P09·P13의 잠금 순서·READ_COMMITTED·3초 잠금 타임아웃·서버 자동 재시도 없음은 유지한다. 테스트가 실패하면 기대값을 완화하지 않고 원인을 확인한다. 기존 구현으로 첫 실행부터 통과하면 인위적인 Red나 생산 코드 변경을 만들지 않는다.

### 충전 응답과 최종 잔액의 구분

| 사용자 행을 먼저 변경한 요청 | 충전 요청이 반환한 잔액 | 두 요청 종료 후 DB 잔액 |
| --- | ---: | ---: |
| 충전 → 주문 확정 | 12,000 | 5,000 |
| 주문 확정 → 충전 | 5,000 | 5,000 |

충전 응답은 해당 요청이 변경한 시점의 값이다. 이후 별도 주문 요청까지 반영한 최종 잔액과 항상 같다고 검사하지 않는다. 테스트는 충전 응답의 두 유효값을 허용하되 최종 DB의 5,000원은 정확히 검사한다. 두 순서를 모두 강제로 재현하는 실험은 아니다.

## 실행과 검증 범위

부모 테스트 트랜잭션 없이 실제 `PointService`와 `OrderService` 프록시를 두 worker에서 호출한다. 준비·시작 신호만 맞추며 서비스 내부 장벽·sleep·mock·spy·재시도를 넣지 않는다. 준비·시작 대기는 5초, 각 Future 완료와 executor 종료 대기는 10초로 제한하고 finally에서 대기 해제·취소·executor 종료를 수행한다. 모든 worker가 종료된 뒤 별도 DB 조회로 검증하며, 종료를 확인하지 못하면 DB 정리를 차단한다.

[PointChargeOrderConcurrencyIntegrationTest](../../apps/commerce-api/src/test/java/com/loopers/application/order/PointChargeOrderConcurrencyIntegrationTest.java)에 요청별 결과·DB 보존식·무관한 데이터 보존을 모은다. 기존의 다른 금액 충전/결제 경쟁 테스트는 유지하고 이번 과제의 지정 수치 증거와 구분한다.

## 실행 기록

| 단계 | 실제 결과 |
| --- | --- |
| 문서·코드 대조 | 과제 지정 수치·C07/C10·P09/P13과 두 서비스가 공유하는 사용자 잠금 경로 확인. 정책 변경 없음 |
| 테스트 우선 추가 | 신규 1개 테스트에 두 품목 주문·충전·무관한 bob 데이터를 준비하고 실제 DB 경쟁과 저장 결과 검사 |
| 첫 실행 | 2026-10-08 21:27 KST, 신규 1개·Checkstyle test 통과. 업무 Red 없이 첫 실행부터 Green |
| 구현·정리 판단 | 기존 구현이 기대값을 만족하여 서비스·잠금·스키마 변경 없음. 테스트의 실행/정리와 DB 보존 검사를 helper로 분리했고 기존 테스트는 유지 |

### 확인한 저장 결과

| 확인 대상 | 실제 통과한 assertion |
| --- | --- |
| 요청 결과 | 충전 성공 1·확정 성공 1·업무 거절 0·예상 밖 오류 0, 합 2. 충전 반환 잔액은 12,000 또는 5,000 중 유효값 |
| 주문 | 반환된 주문 ID와 DB의 유일한 CONFIRMED 주문 ID 일치. 결제액 7,000·확정 시각 존재, 반환값과 재조회 일치 |
| 재고·잔액 | DB 주문 품목 수량 A=2/B=1만 차감해 재고 각 3. 10,000+성공 충전 2,000−DB 결제 7,000=잔액 5,000, 잔액 서비스 재조회도 동일 |
| 보존 | 주문 품목 스냅샷·6개 테이블 행 ID와 수·허용된 컬럼 외 값 유지. bob 잔액 7,000과 별도 DRAFT·상품·브랜드 유지 |

두 잠금 획득 순서를 모두 강제하거나 DB 잠금 대기를 관찰한 것은 아니다. worker 미종료 시 DB 정리를 차단하지만 그 경로를 별도 장애 주입으로 시험하지는 않았다. HTTP 동시 요청이나 모든 장애 상황으로 완료 범위를 넓히지 않는다.

### 실행 명령과 검사 결과

```bash
./gradlew :apps:commerce-api:test --tests '*PointChargeOrderConcurrencyIntegrationTest' :apps:commerce-api:checkstyleTest --console=plain -q

./gradlew :apps:commerce-api:test --tests '*PointChargeOrderConcurrencyIntegrationTest' --tests '*OrderPointConcurrencyIntegrationTest' --tests '*OrderStockConcurrencyIntegrationTest' --tests '*OrderConcurrencyIntegrationTest' --tests '*OrderTransactionTest' --tests '*OrderServiceIntegrationTest' --tests '*StockLostUpdateControlTest' --tests '*PointServiceIntegrationTest' --tests '*UserPointsTest' --tests '*ArchitectureTest' :apps:commerce-api:checkstyleTest --console=plain -q
```

| 실행 시점 (2026-10-08 KST) | 검사 | 실제 결과 |
| --- | --- | --- |
| 21:27 | 신규 테스트·Checkstyle test | 테스트 1개 통과, 실패·오류·건너뜀 0, Checkstyle test 보고서 위반 0·명령 종료 코드 0 |
| 21:28 | 관련 회귀·ArchUnit·Checkstyle test | 10개 스위트·50개 통과, 실패·오류·건너뜀 0, Checkstyle test 보고서 위반 0·명령 종료 코드 0 |

50개는 신규 충전/결제 1·포인트 경쟁 1·재고 경쟁 1·기존 주문 동시성 7·주문 트랜잭션 4·주문 서비스 3·갱신 유실 대조군 1·포인트 서비스 18·포인트 도메인 13·ArchUnit 1의 합이다. 신규 단독 실행을 합산한 수가 아니다.

이전 전체 59개 스위트·548개 통과는 이번 테스트 추가 전의 기록이다. 이번 변경 후 `:apps:commerce-api:check`는 사용자 요청으로 실행하지 않으며, 전체 검사 건수를 추정해 완료 처리하지 않는다.
