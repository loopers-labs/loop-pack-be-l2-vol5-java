# R02 구현 결과

[요구사항](requirement.md) · [트레이드오프](trade_off/total_trade_off.md) · [구현 계획](plan.md) · [전체 요구사항](../total_requirement.md)

## 1. 구현 결과

- `OrderConfirmationPolicy`가 주문 상태·상품별 총수량·삭제 여부·재고·잔액을 모두 검증한 다음 변경한다. 순수 도메인에서 업무 실패 후 메모리 상태도 보존한다.
- `JpaConfirmOrderWriter`가 주문 → 사용자 Wallet → 중복 제거한 상품 ID 오름차순으로 잠금 조회한다. 도메인의 변경을 기존 JPA 관리 엔티티에 명시적으로 매핑하고 사용·결제 기록과 함께 저장한다.
- 충전·상품 수정·개별 삭제·최종 재고 설정도 변경용 잠금 조회를 사용한다. 브랜드 일괄 삭제는 `LEFT JOIN FETCH` 자체에 비관적 쓰기 잠금을 적용해 도메인 변환 전에 상품 상태를 보호한다.
- `Point`를 `Wallet`으로 전환하고 `Wallet.charge/use`가 `PointBill`을 반환한다. 이력은 컬렉션으로 로드하지 않고 별도 저장소로 저장한다. `OrderBill`은 Pay 소유를 유지하고 `ConfirmOrderService`에서 생성한다.
- 기존 HTTP 경로(`/api/v1/points`, `/api/v1/orders/{orderId}/confirm`)·응답·주문 당시 금액은 유지한다. 합의한 스키마 변경은 `points` → `wallets` 테이블명 전환이며 `point_bills`는 유지한다.

### 호출·SQL·프록시·트랜잭션 경계

```text
POST /api/v1/orders/{orderId}/confirm
  → OrderController
  → ConfirmOrderUseCase의 Spring 프록시
    → ConfirmOrderService.execute (@Transactional, REQUIRED)
      → JpaConfirmOrderWriter.load
        → OrderRepository.findByIdForUpdate → 주문 잠금 조회, 품목 조회
        → WalletRepository.findByUserIdForUpdate → 사용자 잔액 잠금 조회
        → ProductRepository.findByIdForUpdate → 상품 ID 오름차순 잠금 조회
      → OrderConfirmationPolicy.confirm → 전체 검증 후 메모리 상태 변경
      → OrderBill.paid → Application이 Pay 기록 생성
      → JpaConfirmOrderWriter.save
        → 상품·Wallet 관리 엔티티 갱신
        → PointBill(USE)·OrderBill(PAID) INSERT
        → 주문 관리 엔티티 갱신
    → flush·commit 또는 예외 전파·전체 rollback
  → ApiControllerAdvice → 기존 HTTP·ApiResponse 오류 변환
```

서비스 진입점을 외부에서 호출하므로 자기 호출로 프록시를 우회하지 않는다. 하위 Writer에 별도 MANDATORY/REQUIRES_NEW를 추가하지 않고, 조회부터 저장까지 서비스 트랜잭션에 참여한다. 자동 재시도·직접 복구·예외 삼키기도 없다. 기존 Spring Data repository의 트랜잭션 설정은 유지한다.

생성 SQL 순서 전체를 자동 assertion한 것은 아니다. 잠금·저장 호출 순서는 `JpaConfirmOrderWriterTest`로, 실제 경쟁 결과는 MySQL 서비스 통합 테스트로 확인한다. `BrandFindForDeletionLockIntegrationTest`는 다른 커넥션에서 상품 행 잠금 시도가 MySQL 1205로 실패하는지 검사한다. 테스트의 짧은 대기 설정은 잠금 관찰용이며 제품 설정을 변경하지 않는다.

## 2. 실행·검증 결과

실행일: 2026-09-25. Windows / Java 21 / 기존 MySQL 8.0 Testcontainers fixture 사용. 기존 로컬 `docker-java.properties`의 `api.version=1.44` 설정은 Git 제외 상태를 유지했다.

| 실행 | 테스트 | 실패 / 오류 / skip | 종료 결과 |
|---|---:|---|---|
| `gradlew.bat :apps:commerce-api:test --tests 'com.loopers.interfaces.api.ordering.order.OrderApiE2ETest*' --console=plain` | 18 | 0 / 0 / 0 | exit 0, BUILD SUCCESSFUL, 1분 53초 |
| `gradlew.bat :apps:commerce-api:check --console=plain` | 247 | 0 / 0 / 0 | exit 0, BUILD SUCCESSFUL, 5분 15초 |

전체 검사의 XML 결과는 `apps/commerce-api/build/test-results/test/`, HTML은 `apps/commerce-api/build/reports/tests/test/index.html`에 생성된다. Checkstyle은 모듈의 main/test 소스에 연결되어 있고 검사 완화 없이 실행한다. ArchUnit은 `com.loopers`를 읽어 계층 의존 5개와 mall/shopping/ordering/pay/shared의 도메인 순수성 2개 규칙을 검사하므로 신규 Wallet·OrderConfirmationPolicy·JPA Writer도 대상에 포함한다.

Checkstyle main/test 위반은 0건이다. 전체 247건에는 순수 도메인 정책 9건, JPA Writer 2건, SQL 이후 롤백 1건, 갱신 유실 대조군 1건, 실제 서비스 경쟁 6건, 브랜드 상품 잠금 1건, R01 브랜드 롤백 1건, ArchUnit 7건 및 기존 회귀 테스트가 포함된다. 위 주문 HTTP 18건은 전체 검사에도 포함되므로 합산해 265건으로 계산하지 않는다.

### 핵심 테스트와 증거

- `OrderApiE2ETest`: 주문 생성 4·확정 9·고객 조회 3·관리자 조회 2건. 기존 성공·재고/잔액 부족·재확정을 유지하고 삭제 상품 404와 SQL 이후 저장 실패 500을 추가했다.
- `returnsInternalServerError_whenSaveFailsAfterRealSql`: `OrderRepository` spy가 실제 저장 후 `EntityManager.flush()`를 실행한다. 같은 트랜잭션의 native SELECT로 재고 10 → 8을 관찰하고 예외를 던진다. HTTP 응답 후 별도 DB 조회로 재고 10·잔액 10,000·DRAFT·신규 사용/결제 기록 0을 확인하고 기존 기술 오류 응답의 code/message도 확인한다.
- `ConfirmOrderSqlRollbackIntegrationTest.rollsBackEverything_afterRealChangeSqlAlreadyApplied`: 여러 품목에서 실제 변경 SQL 후 실패를 주입한다. 종료 후 전체 재고·잔액·DRAFT·신규 USE/PAID 부재, 기존 충전 기록과 무관한 주문·상품의 보존을 검증한다. 마지막 검증 단계에서 기존 충전 기록을 실제 저장하도록 fixture를 보완했다.
- `StockLostUpdateControlGroupTest`: 제품 서비스와 분리한 두 JDBC 트랜잭션이 재고 5를 읽고 각각 4를 저장한다. 성공 2·최종 재고 4로 갱신 유실을 정상 assertion하며 실제 서비스의 정합성 증거로 혼동하지 않는다.
- `ConfirmOrderConcurrencyIntegrationTest`: 아래 6개 사례에서 기술 오류 0과 성공·업무 거절 집계를 확인한다. 마지막 검증 단계에서는 성공/거절별 주문 상태·품목 수량·사용/결제 기록의 건수와 금액, 차감되지 않아야 하는 재고·잔액 검증을 보강했다.

| 메서드 | 기대 및 검증 결과 |
|---|---|
| `concurrentReconfirm_succeedsOnce` | 성공 1·재확정 거절 1, 차감·USE·PAID 각 1회 |
| `concurrentStockRace_acceptsUpToAvailableStock` | 요청 8·성공 5·재고 부족 3·최종 재고 0, 거절 사용자의 잔액 보존 |
| `concurrentBalanceRace_acceptsUpToAvailableBalance` | 요청 3·성공 2·잔액 부족 1·잔액 2,000, 거절 주문의 재고 보존 |
| `concurrentChargeAndConfirm_bothSucceed` | 충전·확정 모두 성공, 잔액 5,000, 충전 기록 2,000과 사용/결제 기록 7,000 |
| `concurrentSetStockAndConfirm_matchesSequentialOutcome` | 모두 성공, 재고 8 또는 10, 잔액 8,000과 결제 기록 |
| `concurrentReversedItemOrder_bothSucceedWithoutDeadlock` | 모두 성공, 두 상품 재고 각 3, 두 사용자 잔액 각 8,000 |

준비 데이터는 요청 전에 저장·커밋하고 실제 Spring bean을 worker별로 호출한다. 테스트 전체에 부모 트랜잭션을 두지 않는다. 실제 서비스 테스트는 시작만 동기화하며 결과를 받은 후 별도 DB 조회로 검증한다. 대조군만 두 읽기가 끝날 때까지 추가 장벽을 사용한다.

## 3. 계획과의 차이

- 구현 중 Wallet 모델·OrderBill 경계·브랜드 삭제 잠금이 추가로 합의되어 계획이 8개 구현 단위로 바뀌었다. 관련 근거는 트레이드오프 07·08·09에 기록했다.
- 최초 계획의 브랜드 상품별 `refresh` 대신 잠금 fetch join을 채택했다. 상품 행까지 보호되는지는 실제 커넥션 경쟁으로 확인했다.
- 실패 주입은 Spring Data 인터페이스 프록시 대신 도메인 저장소 구현의 spy를 사용한다. 같은 트랜잭션의 SQL 관찰에는 EntityManager를, 종료 후 저장 결과 확인에는 별도 repository/JdbcClient 조회를 사용한다.
- 상품 정보 수정·개별 삭제·브랜드 삭제와 확정의 별도 경쟁 테스트는 사용자와 합의해 제외했다. 잠금 적용과 삭제 후 거절·롤백 회귀 검증은 유지한다.
- 마지막 커밋은 테스트 보강과 제출 정리다. 제품 코드는 변경하지 않았으며, 보강한 HTTP 테스트는 기존 구현에서 처음부터 통과했다. 실제로 관찰하지 않은 Red 단계를 기록하지 않는다.

## 4. 한계와 후속 검토

- 주문·Wallet·상품의 실제 잠금 SQL 전체와 획득 순서를 로그 기반 자동 assertion으로 검증하지 않았다. 계획의 해당 항목은 부분 완료로 유지한다.
- 강제 데드락·잠금 timeout을 HTTP에서 각각 주입하지 않았다. 기술 오류 응답은 실제 SQL 후 런타임 저장 실패로 확인했으며 모든 기술 오류 종류의 재현을 의미하지 않는다.
- `ConcurrentRequests`는 시작 10초·결과 공통 마감 30초, future 취소 및 finally의 `shutdownNow`를 사용한다. JDBC connection은 try-with-resources로 반환한다. timeout·인터럽트 경로의 종료/누수 검증을 별도 테스트로 수행하지 않았고 executor 종료 완료를 기다리는 assertion도 없다.
- 잠금 방식·격리 수준·처리량·지연 비교는 하지 않았다. 역순 품목 테스트 통과가 모든 실행에서 데드락이 없음을 증명하지는 않는다.
- `points` → `wallets`에 대한 운영 데이터 마이그레이션은 범위 밖이다. 기존 데이터가 있는 환경에 적용하려면 별도 마이그레이션이 필요하다.
- 전체 테스트의 시간 최적화는 수행하지 않았다. EntityManager/JdbcClient 교체만의 성능 효과는 측정하지 않았으며, 이후 컨텍스트 기동·컨테이너 준비·매 테스트 전체 테이블 TRUNCATE 비용을 나누어 측정할 수 있다.

## 5. 회고

잠금은 도메인이 판단할 상태를 보호하고, 트랜잭션은 변경 전체의 성공·실패를 묶는다. 이 두 책임을 분리한 덕분에 순수 도메인 테스트와 실제 DB 경쟁·롤백 테스트가 서로 다른 근거를 제공한다.

`save()` 호출이나 HTTP 500만으로 SQL 이후 전체 롤백을 증명할 수는 없다. flush 후 변경된 값을 직접 관찰하고 실패 이후 저장된 값을 다시 읽어야 한다. 동시성 검증도 최종 재고·잔액만 확인하면 결제 기록 누락이나 거절 주문의 부분 반영을 놓칠 수 있어 주문별 상태·기록 대조가 필요했다.
