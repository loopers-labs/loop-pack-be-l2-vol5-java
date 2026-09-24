# R02 구현 계획

[요구사항](requirement.md) · [트레이드오프](trade_off/total_trade_off.md) · [전체 요구사항](../total_requirement.md)

작업 브랜치: `volume-3/r02-order-consistency` · PR 대상: `volume-3/main`

상태: 커밋 1(Point→Wallet 전환)·커밋 2(주문 확정 도메인 규칙 분리)·커밋 3(JPA 잠금 조회·저장)·커밋 4(같은 행을 변경하는 기존 경로 보호)·커밋 5(실제 SQL 이후 전체 롤백)·커밋 6(갱신 유실 대조군과 동시 실행 테스트 기반)·커밋 7(실제 서비스 동시성 검증, 필수 6개 시나리오로 범위 한정) 구현·테스트 통과. 커밋 8(HTTP·회귀 검사와 결과 문서)도 완료했다. 최종 check 247건 통과(실패·오류·skip 0), Checkstyle 위반 0건이며 상세 결과와 남은 검증 한계는 [result.md](result.md)에 기록했다. R01 병합 내용은 현재 작업 브랜치에 반영되어 있다.
구현 착수 중 [사용자 잔액 도메인 모델링](trade_off/07-wallet-model.md), [OrderBill 생성 책임과 컨텍스트 경계](trade_off/08-bill-creation-boundary.md), [브랜드 삭제 상품 잠금](trade_off/09-brand-delete-product-lock.md) 트레이드오프가 추가로 합의되었다. 기존 커밋 1~7은 커밋 2~8로 번호를 옮겼다.

## 문서와 진행 원칙

이 문서는 R01과 같은 커밋별 목표·TODO·완료 기준 형식으로 작성한다.
최초 계획 작성 단계는 문서 정리만 범위였으며, 이후 사용자 요청에 따라 구현·검증·커밋·PR 제출까지 진행했다.
구현 체크박스는 실제 완료 상태를 반영하며, 실행 결과는 [result.md](result.md)에 기록한다. 미실행·제외 항목은 구분해 남긴다.

각 구현 커밋은 **실패 테스트 확인 → 구현 → 리팩터링 → 관련 테스트 통과 → 커밋** 순서로 진행한다.
기존 동작이 처음부터 통과하면 그대로 기록한다. 아래 커밋은 이후 구현 작업을 나누는 단위이며 테스트와 해당 구현·보완을 함께 커밋한다.

## 공통 설계

- 기존 API·응답·업무 오류·주문 당시 금액을 유지한다. 추가 합의에 따라 잔액 테이블명은 `points` → `wallets`로 전환한다. 인증·인가 추가는 없다.
- 호출은 `Controller → ConfirmOrderService.execute(REQUIRED) → 잠금 조회 → 순수 도메인 서비스 → JPA 저장 → 커밋`으로 구성한다.
- 주문 확정의 잠금 순서는 **주문 → 해당 사용자 포인트 → 상품 ID 오름차순**으로 고정한다. 상품은 중복 ID를 제거하고 하나씩 잠근다.
- 잠금부터 저장까지 같은 트랜잭션을 유지한다. 하위 MANDATORY·REQUIRES_NEW·자동 재시도·별도 잠금 대기 설정은 추가하지 않는다.
- 기존 조회는 유지하고 변경용 조회 계약을 추가한다. JPA 엔티티와 순수 도메인의 분리, 명시적인 저장 호출을 유지한다.
- 도메인 판단 전에 최신 상태를 확보한다. 잠금 없는 조회로 이미 적재한 엔티티에 잠금만 추가하고 오래된 값을 사용하는 구성을 피한다.

## 커밋 1 — Point를 Wallet으로 전환

**목표:** 사용자 잔액 도메인의 이름·이력 생성 책임을 정리해 이후 커밋(도메인 규칙 분리, 락 도입)의 기반을 만든다. [트레이드오프](trade_off/07-wallet-model.md) 참고.

- [x] `domain.pay.point` → `domain.pay.wallet`로 패키지를 옮기고 `Point`→`Wallet`, `PointRepository`→`WalletRepository`로 이름을 바꾼다. `PointBill`/`PointBillRepository`는 이름을 유지한 채 같은 패키지로 옮긴다.
- [x] `application.pay.point` → `application.pay.wallet`. `PointService`→`WalletService`, `ChargePointUseCase`→`ChargeWalletUseCase`, `PointCommand`→`WalletCommand`, `PointResult`→`WalletResult`, `PointQueryDao`→`WalletQueryDao`로 이름을 바꾼다.
- [x] `infrastructure.pay.point` → `infrastructure.pay.wallet`. `PointJpaEntity`→`WalletJpaEntity`(테이블 `points`→`wallets`, 컬럼명 유지), `PointJpaRepository`→`WalletJpaRepository`, `PointRepositoryImpl`→`WalletRepositoryImpl`, `PointEntityMapper`→`WalletEntityMapper`, `JdbcPointQueryDao`→`JdbcWalletQueryDao`로 이름을 바꾼다. `PointBillJpaEntity`(테이블 `point_bills` 유지)/`PointBillJpaRepository`/`PointBillRepositoryImpl`/`PointBillEntityMapper`는 이름을 유지한 채 같은 패키지로 옮긴다.
- [x] `interfaces.api.pay.point` → `interfaces.api.pay.wallet`. `PointController`→`WalletController`, `PointQueryController`→`WalletQueryController`, `PointApiDto`→`WalletApiDto`로 이름을 바꾸되 `@RequestMapping` 경로(`/api/v1/points`, `/api/v1/points/charge`)와 요청·응답 필드는 그대로 유지한다.
- [x] `Wallet.charge(Money)`가 `PointBill`을 생성해 반환하고, `WalletService`가 그 반환값을 `PointBillRepository.save`로 저장하도록 바꾼다(기존에는 서비스가 직접 `PointBill.charge(...)`를 호출했다).
- [x] `application.ordering.order`(`ConfirmOrderLoad`, `ConfirmOrderService`, `ConfirmOrderWriter`, `infrastructure.ordering.order.JdbcConfirmOrderWriter`)의 `Point`/`PointRepository` 참조를 `Wallet`/`WalletRepository`로 갱신한다. 이 커밋에서는 사용 시점의 `PointBill` 생성 방식(반환값 사용)은 바꾸지 않고, 다음 커밋(도메인 규칙 분리)에서 `Wallet.use(...)`가 `PointBill`을 반환하도록 정리한다.
- [x] 테스트 파일·클래스명(`PointTest`→`WalletTest`, `PointServiceTest`→`WalletServiceTest`, `PointRepositoryIntegrationTest`→`WalletRepositoryIntegrationTest`, `PointApiE2ETest`→`WalletApiE2ETest` 등)과 내부 변수명을 함께 갱신한다. `PointBillTest`는 이름을 유지한다.
- [x] 이름만 바뀌었을 뿐 기존 동작(HTTP 계약·업무 규칙)이 그대로임을 관련 테스트로 확인하고 커밋한다.

**완료 기준:** `Point` 참조가 남아있지 않고(`PointBill` 계열 제외), 기존 API·업무 규칙·테스트가 이름 변경 전과 동일하게 통과한다.

## 커밋 2 — 주문 확정 도메인 규칙 분리

**목표:** 주문·상품·잔액의 업무 흐름을 순수 도메인 서비스로 묶고, 업무 검증 실패 시 메모리 상태도 보존한다.

- [x] Ordering 도메인에 `OrderConfirmation`과 사용·결제 기록을 반환하는 결과 타입을 추가한다. Spring·DB·Application 타입에 의존하지 않는다.
- [x] `Stock`에 non-mutating `ensureCanDecrease(int)`를 추가하고 `decrease(int)`가 내부에서 재사용하도록 리팩터한다. `Wallet`에 non-mutating `ensureSufficientBalance(Money)`를 추가하고 `use(Money, long orderId)`가 재사용하도록 리팩터한다(값 검증·변경 책임은 VO/애그리거트 자신에게 둔다). (`Product.ensureCanDecreaseStock`, `Order.ensureCanConfirm`도 같은 패턴으로 추가)
- [x] 주문 상태 → 상품별 총수량·삭제 여부·재고 → 저장된 주문 합계에 대한 잔액 순서로, 위 non-mutating 검증 메서드를 먼저 전부 호출한 뒤(1차), 실제 mutate 메서드를 호출(2차)하는 순서로 도메인 서비스를 구성한다. (`OrderConfirmationPolicy.confirm`)
- [x] `Wallet.use(Money, long orderId)`가 `PointBill`을 생성해 반환하도록 한다(같은 컨텍스트 애그리거트가 자기 기록 생성을 책임지되, 영속화는 `PointBillRepository`로 독립적으로 수행 — Wallet이 Bill을 컬렉션으로 소유하지 않는다). `Order.confirm()`은 반환값 없이 상태만 바꾸고, `OrderBill`은 지금처럼 `ConfirmOrderService`(application)가 생성한다 — `Order`(ordering)와 `OrderBill`(pay.orderbill)은 다른 컨텍스트라 애그리거트가 서로의 기록을 대신 만들 수 없다([trade_off/08-bill-creation-boundary.md](trade_off/08-bill-creation-boundary.md)).
- [x] 동일 상품 품목은 총수량으로 검증하고 차감한다. 수량 합산 초과는 기존 계산 초과 오류를 사용한다.
- [x] 정상·정확한 재고와 잔액·1 부족·뒤쪽 품목 실패·삭제 상품·재확정·중복 품목·계산 초과를 검증한다. 실패 후 주문·전체 상품·잔액 상태가 유지되어야 한다. (`OrderConfirmationPolicyTest`)
- [x] `ConfirmOrderService`는 조회·도메인 호출·저장·응답 조합만 담당하도록 연결한다. 이 커밋에서는 기존 저장 구현을 사용한다. (`JdbcConfirmOrderWriter` 그대로)
- [x] 도메인·서비스 단위 테스트와 관련 기존 통합 테스트가 통과하면 커밋한다. (`./gradlew :apps:commerce-api:test` 전체 통과 확인)

**완료 기준:** DB 없이 전체 업무 규칙과 실패 후 상태 보존을 검증하고 기존 API 동작을 유지한다. — 충족.

## 커밋 3 — 주문 확정의 JPA 잠금 조회·저장

**목표:** 보호된 최신 상태로 판단하고 모든 변경을 같은 JPA 트랜잭션에 저장한다.

- [x] 주문·포인트·상품에 변경용 조회 계약을 추가하고 Infrastructure에서 `PESSIMISTIC_WRITE`로 구현한다. 일반 조회에 잠금을 강제하지 않는다. (`findByIdForUpdate`/`findByUserIdForUpdate`, 기존 `findById`와 별개 메서드)
- [x] `ConfirmOrderWriter`와 `ConfirmOrderLoad` 계약을 유지하면서 구현을 `JpaConfirmOrderWriter`로 교체한다.
- [x] 주문 행을 먼저 잠근 뒤 변경되지 않는 주문 품목을 읽고, 포인트와 상품을 정해진 순서로 잠근다. (`JpaConfirmOrderWriterTest`로 호출 순서 확인)
- [x] 기존 repository·mapper로 변경을 관리 엔티티에 반영하고 USE·PAID 기록도 JPA로 저장한다. 기존 주문 품목을 재생성하지 않는다.
- [ ] 저장소 통합 테스트에서 실제 잠금 SQL·획득 순서·트랜잭션 참여를 확인한다. flush 후 영속성 컨텍스트를 비우고 재조회한다. — **부분 완료**: 잠금·저장 호출 순서는 mock 기반 단위 테스트(`JpaConfirmOrderWriterTest`)로 확인했고 실제 MySQL로 정상 동작함은 `ConfirmOrderIntegrationTest`로 확인했다. 다만 생성된 SQL에 실제로 `for update`가 포함되는지 쿼리 로그를 직접 검사하지는 않았다 — 필요해지면 재검토한다.
- [x] 주문 당시 품목·단가·총액·생성 정보와 상품의 이름·가격·삭제 상태 등 무관한 값이 보존되는지 검증한다. (`ConfirmOrderIntegrationTest.confirmsOrder_withStockPointAndRecords`)
- [x] 참조가 없어진 주문 확정 JDBC 구현을 제거하고 관련 테스트가 통과하면 커밋한다.

**완료 기준:** 주문 확정이 도메인 판단 전 잠금을 확보하고, 재고·포인트·주문·기록을 JPA로 함께 저장한다. — 충족(잠금 SQL 로그 직접 검사는 남은 한계로 기록).

## 커밋 4 — 같은 행을 변경하는 기존 경로 보호

**목표:** 충전·상품 변경·브랜드 일괄 삭제가 주문 확정 결과를 오래된 값으로 덮어쓰지 않도록 한다.

- [x] 포인트 충전은 변경용 포인트 조회 후 충전·저장·기록 생성을 수행한다. (`WalletService.execute` → `findByUserIdForUpdate`)
- [x] 상품 수정·개별 삭제·최종 재고 설정은 변경용 상품 조회 후 판단·저장한다. 현재 mapper가 재고를 포함한 전체 값을 반영하므로 세 경로에 같은 규칙을 적용한다. (`ProductService.findProduct` → `findByIdForUpdate`, 세 UseCase가 공용으로 사용)
- [x] 브랜드 일괄 삭제는 R01의 LEFT JOIN FETCH와 브랜드 단위 저장을 유지한다.
- [x] 일괄 삭제에서 조회한 상품이 최신 DB 값으로 보호된 채 도메인으로 변환되도록 한다 — 단, 방식은 계획 당시의 `refresh`+개별 잠금이 아니라 [trade_off/09-brand-delete-product-lock.md](trade_off/09-brand-delete-product-lock.md)에서 다시 정한 대로 `BrandJpaRepository.findForDeletion` 조회 자체에 `@Lock(PESSIMISTIC_WRITE)`를 걸어 브랜드+상품 전체를 한 쿼리로 잠갔다(개별 `refresh` 불필요, SQL 왕복도 더 적음).
- [x] 잠금 획득 전에 상품을 변경하거나 저장하지 않는다. 보호된 상품 상태를 삭제 처리와 저장까지 유지한다.
- [x] 단위 테스트로 변경용 조회 사용을, MySQL 통합 테스트로 실제 잠금과 무관한 값 보존을 검증한다. (`BrandFindForDeletionLockIntegrationTest`로 별도 커넥션의 짧은 잠금 대기 타임아웃을 직접 확인)
- [x] R01 일괄 삭제·전체 롤백·삭제 후 확정 거절 테스트가 통과하면 커밋한다. (`DeleteBrandRollbackIntegrationTest`, `ConfirmOrderIntegrationTest` 재확인)

**완료 기준:** 운영 코드에서 같은 재고·잔액을 저장하는 경로가 판단 전 보호 규칙을 지킨다. 브랜드 삭제와 상품 등록의 동시 실행은 기존 제외 범위를 유지한다. — 충족.

## 커밋 5 — 실제 SQL 이후 주문 확정 전체 롤백

**목표:** 변경 SQL이 실행된 이후 실패해도 이번 확정의 모든 변경이 취소됨을 증명한다.

- [x] 여러 품목·기존 거래 기록(충전)·무관한 다른 주문을 포함하는 데이터를 먼저 커밋한다.
- [x] 테스트 전용 spy에서 실제 저장을 호출한 뒤 flush한다. 같은 트랜잭션의 조회로 변경 SQL의 반영을 확인하고 런타임 예외를 발생시킨다. (도메인 레벨 `OrderRepository`를 spy 대상으로 사용 — Spring Data가 생성하는 `OrderJpaRepository` 인터페이스 프록시는 `callRealMethod()`를 지원하지 않아 `MockitoException`이 났다. 조회는 `JdbcClient` 대신 같은 영속성 컨텍스트를 보장하는 `EntityManager` 네이티브 쿼리 사용)
- [x] 실제 Spring 서비스 프록시를 호출하고, 테스트 전체를 부모 트랜잭션으로 감싸지 않는다.
- [x] 예외가 서비스 밖으로 전파된 뒤 별도 DB 조회로 DRAFT·전체 재고·잔액·기존 기록 보존과 신규 USE·PAID 기록 부재를 확인한다.
- [x] 제품 코드에 실패 주입 분기·직접 복구·예외 삼키기를 추가하지 않는다. (신규 테스트 파일 1개만 추가, 제품 코드 변경 없음)
- [x] 관련 롤백·정상 확정 테스트가 통과하면 커밋한다. (`ConfirmOrderSqlRollbackIntegrationTest` 및 `application.ordering.order`·`interfaces.api.ordering.order`·ArchUnit 회귀 통과)

**완료 기준:** 저장 전 검증 실패와 구분되는, 실제 SQL 실행 이후 전체 롤백 증거가 있다. — 충족.

## 커밋 6 — 갱신 유실 대조군과 동시 실행 테스트 기반

**목표:** 보호 없는 갱신 유실을 재현하고 이후 경쟁 검증에 사용할 실행·결과 수집 기반을 준비한다.

- [x] 기존 MySQL 8.0 fixture에서 독립된 두 connection·transaction이 재고 5를 읽도록 한다. (`StockLostUpdateControlGroupTest`, 제품 코드 미사용 — 순수 JDBC)
- [x] 두 읽기 결과가 모두 5임을 확인한 뒤 테스트 전용 장벽을 해제한다. (`CyclicBarrier`로 양쪽 읽기 완료 후에만 쓰기 진행)
- [x] 각 트랜잭션이 상수 4를 저장하고 커밋하도록 한다. 성공 2·최종 재고 4와 수량식 불일치를 정상 assertion으로 검증한다.
- [x] 대조군은 테스트 소스에만 두고 실제 서비스의 정합성 증거와 구분한다.
- [x] worker 실행 도구는 요청별 성공·업무 거절·기술 오류를 수집한다. 준비 데이터 커밋, 시작 동기화, 제한 시간, finally 정리를 공통화한다. (`ConcurrentRequests` — `Outcome<T>`로 성공 값/예외를 모아 반환, 업무 거절·기술 오류 구분은 커밋 7에서 호출부가 예외 타입으로 분류)
- [x] 시작 대기는 10초, 전체 결과 수집은 공통 마감 시각 기준 30초로 제한한다. 실패 시 대기를 해제하고 작업 취소·executor 종료·connection 반환을 수행한다.
- [ ] 대조군과 자원 정리 검증이 통과하면 커밋한다. — 대조군은 통과했다. timeout·인터럽트 경로의 종료/누수에 대한 별도 테스트와 executor 종료 완료 assertion은 미실행이며 [결과 문서](result.md)에 한계로 남긴다.

**완료 기준:** 갱신 유실 재현과 요청별 결과 수집은 충족. 실패 시 자원 정리의 모든 분기에 대한 독립 검증은 미실행이다.

## 커밋 7 — 실제 서비스 동시성 검증

**목표:** 실제 Spring 서비스의 성공·거절 결과와 최종 DB 상태가 일치함을 증명한다.

- [x] 같은 주문 2회 확정: 성공 1·기존 상태 오류 1, 차감·USE·PAID 기록 각각 한 번.
- [x] 재고 5에 서로 다른 사용자 주문 8건: 성공 5·재고 부족 3·최종 재고 0.
- [x] 잔액 10,000원에 4,000원 주문 3건: 성공 2·잔액 부족 1·최종 잔액 2,000원.
- [x] 잔액 10,000원에서 2,000원 충전과 7,000원 확정: 둘 다 성공·최종 잔액 5,000원.
- [x] 초기 재고 5에서 최종 재고 10 설정과 수량 2 주문: 둘 다 성공하며 최종 재고는 순차 실행에 해당하는 8 또는 10.
- [x] 동일한 두 상품을 반대 품목 순서로 가진 주문도 함께 성공하는지 확인해 상품 잠금 순서를 검증한다.
- [ ] ~~상품 정보 수정·개별 삭제·브랜드 일괄 삭제와 확정의 경쟁도 검증한다.~~ **의도적으로 생략.** requirement.md의 완료 조건(숫자로 명시된 필수 경쟁 사례)에는 포함되지 않은 plan.md 자체 체크리스트 항목이며, 해당 경로들의 잠금(변경용 조회)은 이미 커밋 3(`findByIdForUpdate`)·커밋 4(`ProductService`/`WalletService`)·트레이드오프 09(`findForDeletion` 잠금)로 구조적으로 검증됐다. 사용자와 협의해 범위에서 제외하기로 함 — 별도 동시성 테스트는 만들지 않는다.
- [x] 모든 사례에서 기술 오류 0, 요청 집계 일치, 성공량에 따른 수량·잔액식, 거절 요청의 부분 반영 부재를 확인한다.
- [x] 실제 서비스 테스트는 시작만 동기화한다. 제품 코드의 잠금 구간에 장벽·sleep을 넣거나 통과할 때까지 재시도하지 않는다. (`ConcurrentRequests`는 시작 시점만 동기화)

**완료 기준:** 필수 경쟁 결과와 다른 저장 경로의 보호를 실제 DB의 주문·품목·잔액·재고·기록으로 확인한다. — requirement.md 완료 조건의 필수 수치는 충족. 상품수정/개별삭제/브랜드삭제 vs 확정의 별도 동시성 테스트는 의도적으로 생략(위 사유 참고), 나머지는 `ConfirmOrderConcurrencyIntegrationTest`로 충족.

## 커밋 8 — HTTP·회귀 검사와 결과 문서

**목표:** 기존 외부 계약을 유지하고 R02의 구현·검증 결과를 제출 가능한 상태로 정리한다.

- [x] 실제 Controller·Application·DB를 연결한 대표 성공, 재고·잔액 부족, 재확정, 삭제 상품 오류를 확인한다.
- [x] 주입한 저장 오류가 전체 롤백 후 기존 기술 오류 응답으로 연결되는지 검증한다. 잠금·SQL 오류를 품절이나 잔액 부족으로 바꾸지 않는다.
- [x] R01과 관련 브랜드·상품·포인트·주문 회귀 테스트를 실행한다.
- [x] `./gradlew :apps:commerce-api:check`를 실행하고 Checkstyle·ArchUnit이 신규 코드까지 검사하는지 확인한다.
- [x] `result.md`에 실제 테스트 이름·건수·실패·skip·종료 결과, SQL·프록시·트랜잭션 경계, 계획과의 차이·한계·회고를 기록한다.
- [x] 요구사항·트레이드오프·계획·전체 진행 상태를 실제 결과와 일치시킨다. 완료한 항목만 체크한다.
- [x] 관련 diff와 문서 링크를 확인하고 커밋한다.

**완료 기준:** 요구사항의 완료 조건을 검증 결과로 설명할 수 있으며, 미실행 항목과 한계도 사실대로 기록되어 있다.

실제 실행 결과·계획과의 차이·회고는 [result.md](result.md)에 기록했다. 커밋 3의 SQL 전체 순서 검증과 커밋 6의 실패 시 자원 정리 검증은 남은 한계이며, 커밋 7의 추가 경쟁 사례는 합의에 따라 제외했다.
