# 패키지 구조 리팩토링 계획

[체크리스트](checklist.md) · [결정 기록](context-notes.md) · 결과: `result.md`(작업 후 작성)

작업 브랜치: `volume-3/refacto` · 기준 브랜치: `volume-3/main` · 대상: `apps/commerce-api` (main + test)

상태: 커밋 0~4(domain~interfaces) 완료. 추가 작업 A1~A3(OrderRecord 이관·확정 흐름) 완료·병합,
B1~B2(OrderRecord를 Order 애그리거트에 편입) 완료·병합. 테스트 경량화([test-slimming.md](test-slimming.md))
완료·병합. 커밋 5(결과 문서·구조 설명 갱신) 완료 — 결과는 [result.md](result.md) 참고.
커밋 4는 당시 사용자 요청으로 ArchUnit·전체 테스트 없이 컴파일 + Checkstyle만 통과를 확인했는데,
이 확인은 이후 테스트 경량화 작업 뒤의 전체 `check` 실행(BUILD SUCCESSFUL, 4분 33초, test 211건 + slowTest 17건,
실패 0)으로 커버됐다.

## 배경

현재 모든 레이어가 `layer.context.feature`(예: `application.mall.product`)로 나뉘어 있고, feature 폴더 하나에 역할이 다른 파일이 섞여 있다.
`application.mall.product`에는 UseCase·Service·Command·Result·QueryDao·조회 record 15개가 평평하게 놓여 있고,
조회 모델 이름도 `BrandDetail`, `AdminProduct`, `LikeItem`, `UserQueryModel`처럼 제각각이다.
이번 작업은 feature 대신 **종류(kind)** 기준으로 파일을 다시 묶고 조회 모델 이름을 통일한다. 동작 변경은 없다.

## 목표 구조

- `domain.<ctx>.<kind>`, `application.<ctx>.<kind>`, `interfaces.api.<ctx>.<kind>` — 컨텍스트 우선
- `infrastructure.<kind>.<ctx>[.<subkind>]` — infrastructure만 종류 우선(사용자 결정)
- `<ctx>`: `mall`, `ordering`, `pay`, `shopping`
- 손대지 않는 패키지: `domain.shared`, `domain.support`, `application.common`, `application.support`,
  `interfaces.api` 루트 파일과 `interfaces.api.support`, `support.*`, 모든 `example` 패키지,
  테스트의 `architecture`, `fixtures`, `support`, `CommerceApiContextTest`

## 작업 규칙 (실행자는 반드시 지킨다)

1. **컨텍스트 간 이동 금지.** 파일은 지금 속한 컨텍스트 안에서만 움직인다(예: `ProductLikeCountQueryDao`는 mall에 남는다).
2. **로직 변경 금지.** 바뀌는 것은 `package` 선언, `import`, 아래 명시한 클래스 이름, 아래 명시한 접근제어자뿐이다.
3. **파일 이동은 `git mv`** 로 한다. 파일 첫 줄의 한국어 헤더 주석은 그대로 둔다.
4. **테스트는 대상 클래스와 같은 커밋·같은 패키지로 이동**한다. 아래 매핑표에 테스트 위치를 명시했다.
5. **접근제어 확대는 `UserJpaRepository` → `public` 한 건만 허용**한다. mapper는 JpaEntity와 같은 `entity` 폴더에 두므로
   JpaEntity의 package-private 생성자는 그대로 둔다. 그 밖에 가시성 때문에 컴파일이 깨지면 **임의로 넓히지 말고 멈추고 보고**한다.
6. 같은 패키지였다가 다른 패키지로 갈라진 타입은 명시적 `import`를 추가한다. Checkstyle은 와일드카드 import와
   미사용 import를 금지하므로 `import ...*;`를 쓰지 않고, 이동 후 필요 없어진 import는 지운다.
7. 이름 변경은 **타입 이름만** 바꾼다. 변수명·메서드명·JSON 필드명은 바꾸지 않는다(응답 JSON 형태 불변).
8. Checkstyle·ArchUnit 규칙이나 테스트 기대값을 완화하지 않는다.
9. 커밋 메시지는 기존 형식을 따른다: `타입: 한글 요약` 제목 + 빈 줄 + `- ` 불릿 본문. **`Co-Authored-By` 줄을 넣지 않는다.**

## 커밋 계획

| # | 커밋 제목 | 범위 |
|---|---|---|
| 0 | `docs: 패키지 구조 리팩토링 계획과 결정 사항 정리` | docs/refactor/plan.md, checklist.md, context-notes.md |
| 1 | `refactor: domain 패키지를 컨텍스트·종류 구조로 재배치` | domain + 이를 import하는 모든 파일 |
| 2 | `refactor: infrastructure 패키지를 종류·컨텍스트 구조로 재배치` | infrastructure + 관련 테스트 |
| 3 | `refactor: application 패키지를 컨텍스트·종류 구조로 재배치하고 조회 모델명을 View로 통일` | application + 이를 import하는 infrastructure/interfaces/테스트 |
| 4 | `refactor: interfaces 패키지를 컨텍스트·종류 구조로 재배치` | interfaces + 관련 테스트 |
| 5 | `docs: 패키지 리팩토링 결과와 구조 설명 갱신` | docs/refactor/result.md, CLAUDE.md의 "commerce-api package structure", AGENTS.md 32행 |

각 레이어 커밋에는 해당 단계의 checklist.md 체크와 context-notes.md 추가 기록을 함께 포함한다.

## 커밋 1 — domain (`com.loopers.domain.<ctx>.<kind>`)

| 새 패키지 | 파일 |
|---|---|
| `domain.mall.model` | Brand, Product, Stock |
| `domain.mall.repository` | BrandRepository, ProductRepository |
| `domain.ordering.model` | Order, OrderItem, OrderStatus, OrderConfirmation |
| `domain.ordering.policy` | OrderConfirmationPolicy |
| `domain.ordering.repository` | OrderRepository |
| `domain.pay.model` | Wallet, PointBill, PointBillType, OrderBill, OrderBillStatus |
| `domain.pay.repository` | WalletRepository, PointBillRepository, OrderBillRepository |
| `domain.shopping.model` | User |
| `domain.shopping.repository` | UserRepository |

테스트: BrandTest·ProductTest·StockTest → `domain.mall.model`, OrderTest·OrderItemTest → `domain.ordering.model`,
OrderConfirmationPolicyTest → `domain.ordering.policy`, WalletTest·PointBillTest·OrderBillTest → `domain.pay.model`,
UserTest → `domain.shopping.model`. `domain.shared.MoneyTest`, `domain.support.error.DomainExceptionTest`는 그대로.

참고: `OrderItem.amountAsMoney()`는 package-private이지만 Order와 OrderItem이 모두 `domain.ordering.model`로 가므로 영향 없다.
기존 `domain/mall/brand`, `domain/mall/product`, `domain/ordering/order`, `domain/pay/orderbill`, `domain/pay/wallet`,
`domain/shopping/user` 폴더(main·test)는 비워서 없앤다.

## 커밋 2 — infrastructure (`com.loopers.infrastructure.<kind>.<ctx>`)

| 새 패키지 | 파일 |
|---|---|
| `infrastructure.persistence.mall.entity` | BrandJpaEntity, BrandEntityMapper, ProductJpaEntity, ProductEntityMapper |
| `infrastructure.persistence.mall.jpa` | BrandJpaRepository, ProductJpaRepository |
| `infrastructure.persistence.mall.repository` | BrandRepositoryImpl, ProductRepositoryImpl |
| `infrastructure.persistence.ordering.entity` | OrderJpaEntity, OrderItemJpaEntity, OrderEntityMapper |
| `infrastructure.persistence.ordering.jpa` | OrderJpaRepository |
| `infrastructure.persistence.ordering.repository` | OrderRepositoryImpl |
| `infrastructure.persistence.pay.entity` | WalletJpaEntity, WalletEntityMapper, PointBillJpaEntity, PointBillEntityMapper, OrderBillJpaEntity, OrderBillEntityMapper |
| `infrastructure.persistence.pay.jpa` | WalletJpaRepository, PointBillJpaRepository, OrderBillJpaRepository |
| `infrastructure.persistence.pay.repository` | WalletRepositoryImpl, PointBillRepositoryImpl, OrderBillRepositoryImpl |
| `infrastructure.persistence.shopping.entity` | UserJpaEntity, UserEntityMapper, LikeJpaEntity, ProductLikeCountJpaEntity |
| `infrastructure.persistence.shopping.jpa` | UserJpaRepository (**`public`으로 변경**) |
| `infrastructure.persistence.shopping.repository` | UserRepositoryImpl |
| `infrastructure.query.mall` | JdbcBrandQueryDao, QueryDslProductQueryDao, JdbcProductLikeCountQueryDao, ProductQueryRow |
| `infrastructure.query.ordering` | JdbcOrderQueryDao, OrderHeaderRow, OrderItemRow (Row는 package-private 유지) |
| `infrastructure.query.pay` | JdbcWalletQueryDao |
| `infrastructure.query.shopping` | JdbcUserQueryDao, JdbcLikeQueryDao |
| `infrastructure.dao.ordering` | JpaConfirmOrderWriter |
| `infrastructure.dao.shopping` | JdbcLikeCommandDao, JdbcLikeCountAggregationDao |
| `infrastructure.scheduler.shopping` | LikeCountAggregationScheduler |
| `infrastructure.initializer.shopping` | LocalUserFixtureInitializer |

테스트:

| 새 패키지 | 테스트 |
|---|---|
| `infrastructure.persistence.mall.repository` | BrandRepositoryIntegrationTest, BrandFindForDeletionLockIntegrationTest, ProductRepositoryIntegrationTest, StockLostUpdateControlGroupTest |
| `infrastructure.persistence.ordering.repository` | OrderRepositoryIntegrationTest |
| `infrastructure.persistence.pay.repository` | WalletRepositoryIntegrationTest, PointBillRepositoryIntegrationTest, OrderBillRepositoryIntegrationTest |
| `infrastructure.persistence.shopping.entity` | UserEntityMapperTest, LikeStorageIntegrationTest |
| `infrastructure.persistence.shopping.repository` | UserRepositoryIntegrationTest |
| `infrastructure.query.ordering` | JdbcOrderQueryDaoIntegrationTest |
| `infrastructure.query.pay` | JdbcWalletQueryDaoIntegrationTest |
| `infrastructure.query.shopping` | JdbcUserQueryDaoIntegrationTest, JdbcLikeQueryDaoIntegrationTest |
| `infrastructure.dao.ordering` | JpaConfirmOrderWriterTest |
| `infrastructure.dao.shopping` | JdbcLikeCommandDaoIntegrationTest |
| `infrastructure.scheduler.shopping` | LikeCountAggregationSchedulerTest |
| `infrastructure.initializer.shopping` | LocalUserFixtureInitializerTest, LocalUserFixtureInitializerIntegrationTest |

참고: `modules/jpa`의 `JpaConfig`는 `@EnableJpaRepositories("com.loopers.infrastructure")`, `@EntityScan("com.loopers")`이므로
새 위치도 그대로 스캔된다. 설정 파일은 수정하지 않는다. `infrastructure.example`은 그대로 둔다.

## 커밋 3 — application (`com.loopers.application.<ctx>.<kind>`)

종류 폴더: `usecase`(UseCase 인터페이스), `service`(구현), `command`, `result`, `query`(QueryDao·조회 View·조회 조건),
`dao`(infrastructure가 구현하는 쓰기 전용 계약과 그 입출력 record).

| 새 패키지 | 파일 |
|---|---|
| `application.mall.usecase` | CreateBrandUseCase, UpdateBrandUseCase, DeleteBrandUseCase, CreateProductUseCase, UpdateProductUseCase, DeleteProductUseCase, SetProductStockUseCase |
| `application.mall.service` | BrandService, ProductService |
| `application.mall.command` | BrandCommand, ProductCommand |
| `application.mall.result` | BrandResult, ProductResult |
| `application.mall.query` | BrandQueryDao, ProductQueryDao, ProductLikeCountQueryDao, ProductCriteria, ProductSort, BrandView, BrandSummaryView, ProductSummaryView, ProductDetailView, AdminProductView |
| `application.ordering.usecase` | ConfirmOrderUseCase, CreateOrderUseCase |
| `application.ordering.service` | ConfirmOrderService, OrderService |
| `application.ordering.command` | ConfirmOrderCommand, OrderCommand |
| `application.ordering.result` | ConfirmOrderResult, OrderResult, OrderItemResult |
| `application.ordering.query` | OrderQueryDao, OrderView, AdminOrderView, OrderItemView |
| `application.ordering.dao` | ConfirmOrderWriter, ConfirmOrderLoad |
| `application.pay.usecase` | ChargeWalletUseCase |
| `application.pay.service` | WalletService |
| `application.pay.command` | WalletCommand |
| `application.pay.result` | WalletResult |
| `application.pay.query` | WalletQueryDao |
| `application.shopping.usecase` | LikeCountAggregationUseCase |
| `application.shopping.service` | LikeCountAggregationService |
| `application.shopping.query` | LikeQueryDao, LikedProductView, UserQueryDao, UserView |
| `application.shopping.dao` | LikeCommandDao, LikeCountAggregationDao |

타입 이름 변경(파일명·타입 선언·모든 참조):

| 기존 | 변경 |
|---|---|
| BrandDetail | BrandView |
| BrandSummary | BrandSummaryView |
| ProductSummary | ProductSummaryView |
| ProductDetail | ProductDetailView |
| AdminProduct | AdminProductView |
| LikeItem | LikedProductView |
| UserQueryModel | UserView |

OrderView·AdminOrderView·OrderItemView는 이미 `*View`라 그대로다. 변수명·메서드명은 바꾸지 않는다.
이름 변경은 단어 경계 기준으로 치환한다(`ProductSummary`를 바꿀 때 `ProductSummaryView`가 다시 치환되지 않도록 주의).

테스트: DeleteBrandRollbackIntegrationTest → `application.mall.service`,
ConfirmOrderServiceTest·ConfirmOrderIntegrationTest·ConfirmOrderConcurrencyIntegrationTest·ConfirmOrderSqlRollbackIntegrationTest·OrderServiceTest → `application.ordering.service`,
WalletServiceTest → `application.pay.service`, LikeCountAggregationIntegrationTest → `application.shopping.service`.
`application.support.error.ApplicationExceptionTest`는 그대로.

이 커밋은 이동·이름 변경된 타입을 참조하는 infrastructure·interfaces·테스트의 import도 함께 고친다.
ArchUnit의 `INFRASTRUCTURE_APPLICATION_SERVICE_DEPENDENCY_RULE`(`application\..*Service` 이름 매칭)은 새 구조에서도 그대로 유효하다.

## 커밋 4 — interfaces (`com.loopers.interfaces.api.<ctx>.<kind>`)

| 새 패키지 | 파일 |
|---|---|
| `interfaces.api.mall.controller` | AdminBrandController, BrandQueryController, AdminProductController, ProductQueryController |
| `interfaces.api.mall.dto` | BrandApiDto, ProductApiDto |
| `interfaces.api.ordering.controller` | OrderController, OrderQueryController, AdminOrderQueryController |
| `interfaces.api.ordering.dto` | OrderApiDto |
| `interfaces.api.pay.controller` | WalletController, WalletQueryController |
| `interfaces.api.pay.dto` | WalletApiDto |
| `interfaces.api.shopping.controller` | LikeController, LikeQueryController |

테스트: BrandApiE2ETest·ProductApiE2ETest → `interfaces.api.mall.controller`, OrderApiE2ETest → `interfaces.api.ordering.controller`,
WalletApiE2ETest → `interfaces.api.pay.controller`, LikeApiE2ETest → `interfaces.api.shopping.controller`.
ApiControllerAdviceTest, ContractClassificationTest, ExampleV1ApiE2ETest, `interfaces.api.support.*` 테스트는 그대로.

## 검증

레이어 커밋마다 저장소 루트에서 실행하고 모두 통과해야 커밋한다.

```bash
./gradlew :apps:commerce-api:compileJava :apps:commerce-api:compileTestJava :apps:commerce-api:checkstyleMain :apps:commerce-api:checkstyleTest
./gradlew :apps:commerce-api:test --tests "com.loopers.architecture.*"
```

추가 확인:
- `git grep`으로 이번 커밋에서 옮긴 기존 패키지명(예: `com.loopers.domain.mall.brand`)과 바뀐 기존 타입명이 `apps/commerce-api/src`에 남아 있지 않다.
- 옮긴 뒤 빈 폴더가 남지 않는다.
- `git diff -M --stat`에서 이동 파일이 rename으로 잡히고, 내용 diff는 package/import/이름/허용된 접근제어뿐이다.

커밋 4 이후, 커밋 5 전에 전체 검사를 실행한다(Docker 필요). 결과는 result.md에 기록한다.

```bash
./gradlew :apps:commerce-api:check
```

## 추가 작업 — OrderBill을 ordering의 OrderRecord로 이관하고 확정 흐름 정리

작업 브랜치: `volume-3/refacto-order-record` (`volume-3/refacto`의 커밋 4 `a9abf0d`에서 분기, 완료 후 fast-forward 병합)
결정 근거: [R02 트레이드오프 10](../week3/r02-order-consistency/trade_off/10-order-record-ownership.md)

| # | 커밋 제목 | 범위 |
|---|---|---|
| A1 | `docs: OrderBill을 ordering의 OrderRecord로 옮기는 트레이드오프 정리` | 트레이드오프 10 문서, 08·total_trade_off 링크, 이 절, 체크리스트, 결정 기록 |
| A2 | `refactor: OrderBill을 ordering 컨텍스트의 OrderRecord로 이관` | 이관·이름·테이블 변경만, 동작 불변 |
| A3 | `refactor: 주문 확정을 결제 단계와 주문 기록 단계로 나누고 Order.confirm이 OrderRecord를 반환` | 확정 흐름 변경 |

### 커밋 A2 — 이관·이름 변경 (동작 불변)

| 기존 | 변경 |
|---|---|
| `domain.pay.model.OrderBill` | `domain.ordering.model.OrderRecord` |
| `domain.pay.model.OrderBillStatus` | `domain.ordering.model.OrderRecordStatus` (값 `PAID` 유지) |
| `domain.pay.repository.OrderBillRepository` | `domain.ordering.repository.OrderRecordRepository` |
| `infrastructure.persistence.pay.entity.OrderBillJpaEntity`, `OrderBillEntityMapper` | `infrastructure.persistence.ordering.entity.OrderRecordJpaEntity`, `OrderRecordEntityMapper` |
| `infrastructure.persistence.pay.jpa.OrderBillJpaRepository` | `infrastructure.persistence.ordering.jpa.OrderRecordJpaRepository` |
| `infrastructure.persistence.pay.repository.OrderBillRepositoryImpl` | `infrastructure.persistence.ordering.repository.OrderRecordRepositoryImpl` |
| 테이블 `order_bills`, 제약 `uk_order_bills_order_id` | `order_records`, `uk_order_records_order_id` (JdbcOrderQueryDao SQL, 테스트 SQL 포함) |
| 테스트 `domain.pay.model.OrderBillTest` | `domain.ordering.model.OrderRecordTest` |
| 테스트 `persistence.pay.repository.OrderBillRepositoryIntegrationTest` | `persistence.ordering.repository.OrderRecordRepositoryIntegrationTest` |

- 참조하는 모든 코드(ConfirmOrderWriter, JpaConfirmOrderWriter, ConfirmOrderService, ConfirmOrderResult, OrderView, AdminOrderView, JdbcOrderQueryDao, OrderHeaderRow, 테스트)를 갱신한다.
- 이번 추가 작업에서는 변경 대상 코드 안의 `orderBill*` 변수·메서드명, 한국어 주석의 "결제 기록" 표현도 `orderRecord*`·"주문 기록"으로 맞춘다. 단, API JSON 필드(`paymentAmount`, `paymentStatus`)와 그에 대응하는 record 컴포넌트명은 바꾸지 않는다.
- 도메인 에러 메시지 등 사용자에게 보이는 문구는 바꾸지 않는다.

### 커밋 A3 — 확정 흐름

- `Order.confirm()`이 `OrderRecord`를 반환한다 (`OrderRecord.paid(id, userId, totalAmount)`).
- `OrderConfirmationPolicy.confirm(...)`: ① 전부 검증(주문 상태 → 상품별 재고 → 잔액, 현행 순서·오류 그대로) ② 결제 단계 `wallet.use(...)` → `PointBill`(영수증) ③ 주문 단계 재고 차감 → `order.confirm()` → `OrderRecord`. 단계는 private 메서드와 한국어 주석으로 구분한다.
- `OrderConfirmation`에 `orderRecord`를 추가한다. `ConfirmOrderService`는 기록을 직접 만들지 않고 `confirmation.pointBill()`, `confirmation.orderRecord()`를 저장한다.
- 테스트는 이름·시그니처 변경에 따른 수정과 `Order.confirm()` 반환값 검증 추가만 한다. 오류 우선순위·롤백·동시성 기대값은 바꾸지 않는다. 바꿔야 하면 멈추고 보고한다.

### 검증 (Docker 필요)

```bash
./gradlew :apps:commerce-api:compileJava :apps:commerce-api:compileTestJava :apps:commerce-api:checkstyleMain :apps:commerce-api:checkstyleTest
./gradlew :apps:commerce-api:test --tests "com.loopers.architecture.*" --tests "com.loopers.domain.ordering.*" --tests "com.loopers.domain.pay.*" --tests "com.loopers.application.ordering.*" --tests "com.loopers.infrastructure.persistence.ordering.*" --tests "com.loopers.infrastructure.dao.ordering.*" --tests "com.loopers.infrastructure.query.ordering.*" --tests "com.loopers.interfaces.api.ordering.*"
```

A2·A3 커밋마다 실행한다. `git grep -nwE "OrderBill|OrderBillStatus|OrderBillRepository|order_bills"` 결과가 `apps/`에서 0건이어야 한다.

## 추가 작업 2 — OrderRecord를 Order 애그리거트의 1:1 자식으로

작업 브랜치: `volume-3/refacto-order-aggregate` (`volume-3/refacto`의 `33d6584`에서 분기, 완료 후 fast-forward 병합)
결정 근거: [R02 트레이드오프 11](../week3/r02-order-consistency/trade_off/11-order-record-aggregate.md)

| # | 커밋 제목 | 범위 |
|---|---|---|
| B1 | `docs: OrderRecord를 Order 애그리거트에 포함하는 트레이드오프 정리` | 트레이드오프 11, 10·total_trade_off 링크, 이 절, 체크리스트, 결정 기록 |
| B2 | `refactor: OrderRecord를 Order 애그리거트의 1:1 자식 엔티티로 편입` | 도메인·영속성·확정 흐름·테스트 |

### 커밋 B2 — 변경 내용

도메인
- `OrderRecord`: `orderId` 필드와 관련 검증 제거. 필드는 `id`, `userId`, `amount`, `status`, `createdAt`. 팩토리는 `paid(userId, amount)`, `restore(id, userId, amount, status, createdAt)`.
- `Order`: `private OrderRecord record` 추가(DRAFT면 `null`). `confirm()`은 `void`로 돌리고 내부에서 `record = OrderRecord.paid(userId, totalAmount)`. `getRecord()`는 `Optional<OrderRecord>`.
  `Order.restore(..., OrderRecord record)`에 인자를 추가하고 CONFIRMED이면 기록 필수, DRAFT이면 기록 없음을 검사해 어기면 `IllegalArgumentException`(한국어 메시지). `Order.create`는 기록 없이 만든다.
- `OrderConfirmation`에서 `orderRecord` 제거. `OrderConfirmationPolicy`의 주문 단계는 재고 차감 → `order.confirm()`만 하고 기록을 반환하지 않는다(검증 순서·결제 단계는 그대로).
- `OrderRecordRepository` 삭제.

application
- `ConfirmOrderWriter.save(ConfirmOrderLoad load, PointBill pointBill)`로 단순화.
- `ConfirmOrderService`: `order.getRecord().orElseThrow()`로 `ConfirmOrderResult`의 `paymentAmount`·`paymentStatus`를 채운다. 응답 형태 불변.

영속성
- `OrderRecordJpaEntity`: `long orderId` 컬럼 필드를 `@OneToOne(fetch = LAZY) @JoinColumn(name = "order_id", nullable = false, foreignKey = @ForeignKey(name = "fk_order_records_order_id")) OrderJpaEntity order`로 바꾼다. `@UniqueConstraint(name = "uk_order_records_order_id", columnNames = "order_id")` 유지.
- `OrderJpaEntity`: `@OneToOne(mappedBy = "order", cascade = ALL, orphanRemoval = true) OrderRecordJpaEntity record` 추가와 연결 메서드(`assignRecord` 등 package-private).
- `OrderEntityMapper`: `toDomain`에서 기록 복원, `toNewEntity`·`apply`에서 도메인에 기록이 있고 엔티티에 없으면 새 기록 엔티티를 연결. `OrderRecordEntityMapper`는 필요 없으면 삭제하거나 `OrderEntityMapper`로 흡수(같은 `entity` 패키지라 package-private 생성자 접근 가능).
- `OrderRecordRepositoryImpl`, `OrderRecordJpaRepository` 삭제(다른 사용처가 없을 때).
- `JpaConfirmOrderWriter`: 주문 기록 저장 호출 제거, 주문 저장으로 cascade.
- `JdbcOrderQueryDao` SQL은 테이블·컬럼이 같으므로 바꾸지 않는다.

테스트
- `OrderRecordRepositoryIntegrationTest` 삭제, 그 검증(저장·조회, 주문당 기록 하나)을 `OrderRepositoryIntegrationTest`로 이관: 확정 주문 저장 시 기록이 cascade 저장·복원되는지.
- 새 테스트: `OrderTest` — `confirm()` 후 `getRecord()`가 사용자·총액·`PAID`를 담음, DRAFT는 기록 없음, `restore`가 CONFIRMED+기록 없음·DRAFT+기록 있음을 거절.
- 강제 수정만 허용: 시그니처 변경(`restore` 인자, `confirm()` 반환, `save` 인자, `OrderConfirmation` 인자), CONFIRMED 주문을 기록 없이 만들던 테스트 준비 코드.
- 오류 우선순위·롤백·동시성 결과 등 행위 기대값은 바꾸지 않는다. 바꿔야 하면 멈추고 보고한다.

### 검증 (Docker 필요)

```bash
./gradlew :apps:commerce-api:compileJava :apps:commerce-api:compileTestJava :apps:commerce-api:checkstyleMain :apps:commerce-api:checkstyleTest
./gradlew :apps:commerce-api:test --tests "com.loopers.architecture.*" --tests "com.loopers.domain.ordering.*" --tests "com.loopers.domain.pay.*" --tests "com.loopers.application.ordering.*" --tests "com.loopers.infrastructure.persistence.ordering.*" --tests "com.loopers.infrastructure.dao.ordering.*" --tests "com.loopers.infrastructure.query.ordering.*" --tests "com.loopers.interfaces.api.ordering.*"
```
