# 패키지 구조 리팩토링 결정 기록

[계획](plan.md) · [체크리스트](checklist.md)

계획 단계에서 사용자와 문답으로 합의한 결정과 그 이유를 기록한다. 작업 중 새 결정이 생기면 아래에 이어 붙인다.

## 계획 단계 결정

1. **feature 대신 종류로 묶는다.** `layer.context.feature.File`을 `layer.context.<종류>.File`로 바꾼다.
   feature 폴더 하나에 UseCase·Service·Command·Result·QueryDao·조회 record가 섞여 있어 역할별로 찾기 어렵기 때문이다.
2. **infrastructure만 종류 우선이다.** `infrastructure.<종류>.<ctx>` (`persistence`, `query`, `dao`, `scheduler`, `initializer`).
   persistence 아래는 `<ctx>.{entity,jpa,repository}`로 한 번 더 나눈다. 사용자가 persistence를 최상위 묶음으로 두길 원했고,
   조회 DAO·스케줄러 등도 같은 방식으로 맞췄다.
3. **mapper는 entity 폴더에 둔다.** JpaEntity 8개의 생성자가 package-private이고 mapper만 호출한다.
   mapper를 따로 두면 생성자 8개를 public으로 열어야 해서, 접근제어를 지키는 쪽을 골랐다.
   그 결과 public 확대는 `UserJpaRepository` 한 건뿐이다(UserRepositoryImpl이 다른 폴더에서 사용).
4. **domain 종류는 `model`, `repository`, `policy`다.** `OrderConfirmationPolicy`만 policy로 간다.
5. **application 종류는 `usecase`, `service`, `command`, `result`, `query`, `dao`다.**
   `ConfirmOrderWriter`, `LikeCommandDao`, `LikeCountAggregationDao`처럼 infrastructure가 구현하는 쓰기 전용 계약은
   port 대신 `dao`라는 이름을 쓴다. `ConfirmOrderLoad`는 그 계약의 입출력이므로 같은 `dao`에 둔다.
6. **조회 모델은 모두 `*View`로 통일한다.** BrandDetail→BrandView, BrandSummary→BrandSummaryView,
   ProductSummary→ProductSummaryView, ProductDetail→ProductDetailView, AdminProduct→AdminProductView,
   LikeItem→LikedProductView, UserQueryModel→UserView. 쓰기 결과는 기존대로 `*Result`다.
   타입 이름만 바꾸고 변수·메서드명과 응답 JSON은 그대로 둔다.
7. **interfaces 종류는 `controller`, `dto`다.**
8. **컨텍스트 간 이동은 하지 않는다.** mall에 있는 like 관련 파일(`ProductLikeCountQueryDao` 등)도 그대로 둔다.
9. **테스트는 대상 클래스와 같은 커밋·같은 패키지로 옮긴다.** E2E는 controller, Service·통합 테스트는 service,
   Repository 통합 테스트는 persistence repository로 간다.
10. **커밋은 문서 1 + 레이어별 1 + 결과 문서 1**, 총 6개다. 순서는 domain → infrastructure → application → interfaces.
    타입은 `refactor:`, 기존 커밋처럼 한글 요약 + 불릿 본문, `Co-Authored-By` 없음.
11. **검증은 매 커밋 컴파일 + Checkstyle + ArchUnit, 마지막에 `check` 전체**로 한다(통합 테스트는 Docker가 필요해 속도를 우선).
12. **실행은 Sonnet 에이전트에게 레이어 단위로 위임**하고, 레이어마다 결과를 검토해 사용자에게 보고한 뒤 다음으로 넘어간다.

## 작업 중 기록

- 커밋 1(domain) 실행 중 `OrderConfirmationPolicy`가 `ordering.policy`로 가면서, 같은 패키지에 있던
  `Order`·`OrderItem`·`OrderStatus`·`OrderConfirmation`(모두 `ordering.model`로 이동)에 대한 명시적 import 4개를
  추가로 붙여야 했다. `Brand`는 `Product`와 같은 패키지(`mall.model`)로 함께 이동해 기존 `import Product`가
  중복(redundant) import가 되어 제거했다. `OrderConfirmation`은 `Order`와 같은 패키지로 갔지만 `Product`·`Wallet`·
  `PointBill`은 다른 패키지로 갈라져 그 세 개만 import를 갱신했다.
- 커밋 2(infrastructure) 실행 중 JpaEntity/EntityMapper가 `entity` 폴더로, JpaRepository가 `jpa` 폴더로,
  RepositoryImpl이 `repository` 폴더로 갈라지면서 같은 feature 안에서만 서로 참조하던 타입들도 명시적 import가
  필요해졌다: 각 `*JpaRepository`는 자신의 `*JpaEntity`를, 각 `*RepositoryImpl`은 자신의 `*JpaRepository`·
  `*EntityMapper`·(필요한 경우) `*JpaEntity`를 새로 import했다. `BrandJpaEntity`·`BrandEntityMapper`는 같은 패키지가
  된 `ProductJpaEntity`에 대한 기존 import가 중복이 되어 제거했다. QueryDSL이 생성하는 `Q*JpaEntity`도 원본
  엔티티와 같은 새 패키지에 생성되므로 `QueryDslProductQueryDao`의 `QBrandJpaEntity`·`QProductLikeCountJpaEntity`·
  `QProductJpaEntity` import를 새 경로로 맞췄다. 계획대로 접근제어 확대는 `UserJpaRepository` → `public` 한 건뿐이다.
- 커밋 3(application) 실행 중 UseCase·Service·조회 View가 `usecase`/`service`/`command`/`result`/`query`/`dao`로
  갈라지면서, 같은 feature 안에서만 서로 참조하던 타입 거의 전부에 명시적 import가 필요해졌다: 각 UseCase 인터페이스는
  자신의 `Command`·`Result`를, `BrandService`·`ProductService`·`ConfirmOrderService`·`OrderService`·`WalletService`·
  `LikeCountAggregationService`는 자신이 구현하는 UseCase 인터페이스와 `Command`·`Result`·(필요한 경우) `dao` 타입을,
  `OrderView`·`OrderItemView`·`AdminProductView`는 자신이 감싸는 `Result` 타입을 새로 import했다. 테스트 쪽도 같은
  이유로 `ConfirmOrderCommand`/`ConfirmOrderResult`/`ConfirmOrderWriter`/`ConfirmOrderLoad`,
  `OrderCommand`/`OrderResult`, `WalletCommand`/`WalletResult`, `BrandCommand`, `LikeCountAggregationUseCase` 등을
  새로 import했다(단, 테스트와 같은 패키지로 옮겨진 `ConfirmOrderService`/`OrderService`/`WalletService`는 같은
  패키지라 import가 필요 없어 추가하지 않음). 조회 모델 7개 이름 변경은 단어 경계 치환으로 처리했고,
  `findAdminProduct(s)`처럼 이름을 포함하는 메서드명은 그대로 유지됐다.
- 커밋 4(interfaces) 실행 중 사용자 요청으로 ArchUnit·전체 테스트는 실행하지 않고 컴파일(`compileJava`,
  `compileTestJava`)과 Checkstyle(`checkstyleMain`, `checkstyleTest`)만 통과를 확인했다. ArchUnit 회귀 확인은
  커밋 5 전 전체 `check` 실행 때로 미룬다. Controller와 DTO가 `controller`/`dto`로 갈라지면서, 같은 feature
  안에서 암묵적으로(같은 패키지) DTO를 참조하던 `AdminBrandController`(`BrandApiDto`), `AdminProductController`
  (`ProductApiDto`), `OrderController`(`OrderApiDto`), `WalletController`·`WalletQueryController`
  (`WalletApiDto`)에 명시적 import를 추가했다. `BrandQueryController`·`ProductQueryController`·
  `LikeController`·`LikeQueryController`는 자기 컨텍스트의 DTO를 쓰지 않아 추가 import가 없었다. 테스트 쪽도
  같은 이유로 `BrandApiE2ETest`(`BrandApiDto`), `OrderApiE2ETest`(`OrderApiDto`), `WalletApiE2ETest`
  (`WalletApiDto`)에 새 import를 추가했고, `ProductApiE2ETest`는 이미 있던 `BrandApiDto`의 cross-feature import
  경로를 `mall.dto`로 갱신하면서 `ProductApiDto` import를 새로 추가했다.
- 커밋 A2(OrderBill → OrderRecord) 실행 중 JdbcOrderQueryDao의 4개 SQL(`findOrders`·`findAdminOrders`·
  `findHeader`, 문자열은 3곳)에서 테이블명 `order_bills` → `order_records`와 함께 LEFT JOIN 별칭도
  `ob` → `orec`로 바꿔 테이블명과의 연결성을 유지했다. `modules/jpa`의 `DatabaseCleanUp`은 테이블명을
  하드코딩하지 않아 변경할 필요가 없었다.
- 커밋 A3(확정 흐름) 실행 중 변경 순서가 "재고 차감 → order.confirm() → wallet.use()"에서
  "wallet.use()(결제 단계) → 재고 차감 → order.confirm()(주문 단계)"로 바뀌었다. 검증(주문 상태 → 상품별
  재고 → 잔액)은 여전히 모든 변경보다 먼저 끝나므로 오류 우선순위·롤백 대상은 그대로다. `wallet.use()`가
  내부에서 잔액을 다시 확인하지만 검증을 이미 통과했으므로 새로운 실패 경로는 생기지 않는다.
  `OrderConfirmationPolicy.confirm`은 `pay()`(결제 단계)·`placeOrder()`(주문 단계) private static 메서드로
  나눴다. 기존 단위 테스트(`OrderTest`의 `confirmsDraftOrder`)가 `Order.create`로 만든 id 없는(`null`) DRAFT
  주문에 `confirm()`을 호출하고 있었는데, `confirm()`이 이제 `OrderRecord.paid(id, ...)`를 반환하며 `id`가
  `long`으로 언박싱되어 `NullPointerException`이 났다. 이는 새 반환값이 강제한 수정이라 `Order.restore`로 실제
  id를 가진 DRAFT 주문을 쓰도록 고치고, 같은 테스트에 `OrderRecord` 반환값 검증(주문 id·userId·총액·`PAID`
  상태)을 추가했다. `OrderConfirmationPolicyTest`에는 `orderRecord()`가 주문과 일치하고 `pointBill()`이 `USE`
  영수증인지 확인하는 테스트를 추가했다. 지정된 검증 범위(architecture·domain.ordering·domain.pay·
  application.ordering·infrastructure.persistence/dao/query.ordering·interfaces.api.ordering) 111개 테스트
  (기존 109 + 신규 2) 전부 통과했다.

## 추가 작업 결정 — OrderRecord

13. **OrderBill을 ordering의 OrderRecord로 옮긴다.** 결제 사실은 이미 `PointBill(USE)`이 기록하므로,
    OrderBill을 "주문이 확정됐다는 주문의 사실"로 다시 정의했다. R02 트레이드오프 08번을 대체하며 근거는
    [트레이드오프 10](../week3/r02-order-consistency/trade_off/10-order-record-ownership.md)에 있다.
14. **필드·상태값(`PAID`)은 그대로, API JSON 불변, 테이블은 `order_records`로 바꾼다.**
15. **`Order.confirm()`이 `OrderRecord`를 반환한다.** `Wallet.use()`가 `PointBill`을 반환하는 것과 대칭이다.
16. **확정은 전부 검증한 뒤 결제 단계 → 주문 단계로 변경한다.** 검증 순서(주문 상태 → 재고 → 잔액)와 오류 우선순위는 유지한다.
    영수증은 새 타입 없이 기존 `PointBill(USE)`이다.
17. **별도 브랜치 `volume-3/refacto-order-record`에서 진행하고 관련 테스트 + ArchUnit만 돌린 뒤 fast-forward 병합한다.**
    테스트는 이름·시그니처 변경에 따른 수정과 새 동작 검증 추가만 허용한다.

## 추가 작업 2 결정 — OrderRecord 애그리거트 편입

18. **OrderRecord를 Order 애그리거트의 1:1 자식 엔티티로 둔다.** 주문 없이 존재할 수 없고 주문당 하나이며 확정과 같은
    순간에만 생기므로 루트가 규칙을 지키게 했다. 근거는 [트레이드오프 11](../week3/r02-order-consistency/trade_off/11-order-record-aggregate.md).
19. **`Order`가 `record`를 필드로 보유하고 `confirm()`은 다시 `void`다.** 기록은 `getRecord()`(Optional)로 얻는다.
    `OrderConfirmation`에서 `orderRecord`를 빼고 `ConfirmOrderWriter.save(load, pointBill)`로 단순화한다.
20. **독립 저장소(`OrderRecordRepository`·Impl·JpaRepository)를 없애고 `OrderRepository.save`의 cascade로 저장한다.**
21. **JPA는 `order_records.order_id`가 FK 주인인 `@OneToOne`이다.** 테이블 구조·조회 SQL을 그대로 두기 위해서이며,
    주문 로드마다 기록 조회 1회가 늘어나는 비용을 감수한다. 유니크 제약명 유지, FK `fk_order_records_order_id` 추가.
22. **도메인 `OrderRecord`에서 `orderId`를 뺀다.** 관계로 알 수 있고, id 없는 주문 확정 시 NPE 문제도 사라진다.
    `userId`·`amount`는 확정 시점 스냅샷으로, `status(PAID)`는 API 호환과 향후 상태 확장을 위해 유지한다.
23. **`Order.restore`는 CONFIRMED ⇔ 기록 존재를 검사한다.** 테스트는 이관·강제 수정·새 테스트만 허용하고 행위 기대값은 바꾸지 않는다.

## 커밋 B2 실행 기록

- `OrderRecordJpaEntity`는 `orderId` 대신 FK 소유 쪽 `@OneToOne(fetch = LAZY) @JoinColumn(name = "order_id", ...)`을 갖고,
  `OrderJpaEntity`는 반대편 `@OneToOne(mappedBy = "order", cascade = ALL, orphanRemoval = true)`을 갖는다. `OrderItemJpaEntity`의
  기존 `assignOrder` 패턴을 그대로 따라 `OrderJpaEntity.assignRecord(record)`가 양쪽을 연결한다. Hibernate는 mappedBy 쪽
  `@OneToOne`을 프록시로 지연 로딩하지 못해 주문을 조회할 때마다(엔티티가 관리 상태로 로드되는 시점에) 기록 조회 SELECT가
  하나 더 나간다 — 트레이드오프 11에서 감수하기로 한 비용 그대로다. 별도 fetch join은 추가하지 않았다.
- `OrderRecordEntityMapper`는 `OrderEntityMapper`로 흡수해 삭제했다(둘 다 `entity` 패키지라 package-private 생성자 접근에
  문제가 없었다). `OrderEntityMapper.apply`는 상태 변경(`entity.apply(status)`)에 더해, 엔티티에 아직 기록이 없고 도메인에는
  있으면(확정 저장 경로) 새 기록 엔티티를 만들어 `assignRecord`로 붙인다.
- `OrderRepositoryImpl.save`는 `orderJpaRepository.save(entity)`를 `saveAndFlush`로 바꿨다. IDENTITY 채번은 보통 persist
  시점에 즉시 insert가 나가 id가 채워지지만, 기존 엔티티를 갱신하는 확정 경로는 이미 관리 상태인 엔티티에 `assignRecord`로
  새 자식을 매달아 `save()`(내부적으로 `merge()`)를 호출하는 구조라 새 기록의 id·`@PrePersist`가 채우는 `createdAt`이
  메서드 반환 시점에 확실히 채워져 있다고 보장하기 어려웠다. `saveAndFlush`로 명시적으로 flush해 `mapper.toDomain`이
  `OrderRecord.restore`(id>0·createdAt 필수)에 넘길 값을 항상 갖도록 했다. 최소 변경으로 단어 하나만 바꿨다.
- `OrderRecordRepositoryIntegrationTest`를 삭제하고 그 검증(저장·조회, 주문당 기록 하나)을 `OrderRepositoryIntegrationTest`에
  `savesConfirmedOrder_withCascadedOrderRecord`·`enforcesOneOrderRecordPerOrder` 두 테스트로 이관했다. 후자는
  `order_records` 테이블에 대한 네이티브 COUNT 쿼리로 주문당 기록이 하나임을 확인한다(유니크 제약 위반 자체를 재현하는
  대신, 도메인이 애초에 한 주문에 기록을 두 번 붙일 방법을 주지 않으므로 "하나만 생긴다"를 직접 확인하는 쪽으로 바꿨다).
- 강제 수정: `OrderTest`·`OrderConfirmationPolicyTest`·`ConfirmOrderServiceTest`·`JpaConfirmOrderWriterTest`·
  `OrderServiceTest`의 `Order.restore(...)` 호출에 `record` 인자(DRAFT는 `null`, CONFIRMED는 `OrderRecord.restore(...)`로
  만든 값)를 추가했다. `JdbcOrderQueryDaoIntegrationTest`의 `includesPaymentFields_whenOrderRecordExists`는 삭제된
  `OrderRecordRepository.save(OrderRecord.paid(...))` 대신 `order.confirm()` 후 `orderRepository.save(order)`로 바꿨다
  (cascade 저장 확인을 겸한다). `OrderConfirmationPolicyTest.confirmedOrder`는 CONFIRMED 주문을 만들 때 기록을 함께
  넘기도록 바꿨다.
- 새 테스트: `OrderTest`에 `confirmHoldsPaidOrderRecord_withUserIdAndTotalAmount`(확정 전 `getRecord()`가 비어 있고,
  확정 후 사용자·총액·PAID를 담음), `rejectsRestore_whenConfirmedWithoutRecord`, `rejectsRestore_whenDraftWithRecord`를
  추가했다. 기존 `confirmReturnsPaidOrderRecord_...` 테스트는 `confirm()`이 더 이상 값을 반환하지 않으므로
  `confirmHoldsPaidOrderRecord_...`로 대체했다(시그니처가 강제한 변경).
- 지정된 검증 범위(architecture·domain.ordering·domain.pay·application.ordering·infrastructure.persistence/dao/query.ordering·
  interfaces.api.ordering) 112개 테스트(기존 111 − `OrderRecordRepositoryIntegrationTest` 삭제로 3개 감소 + `OrderRepositoryIntegrationTest`
  신규 2개 + `OrderTest`의 restore 불변식 신규 2개) 전부 통과했다. 오류 우선순위·롤백·동시성 결과 등 행위 기대값은 바꾸지 않았다.
- 커밋 B2(`OrderEntityMapper` 흡수) 실행 중 확인한 구현 세부는 다음과 같다. `OrderRepositoryImpl.save`는 `orderJpaRepository.save(entity)`를
  `saveAndFlush`로 바꿔, `assignRecord`로 갓 매단 자식 엔티티의 id·`@PrePersist`가 채우는 `createdAt`이 `mapper.toDomain` 호출
  시점에 항상 채워져 있도록 보장했다(단어 하나만 바꾼 최소 변경). `OrderRecordJpaEntity`가 FK 주인인 `@OneToOne`을 갖고
  `OrderJpaEntity`가 `mappedBy` 쪽을 가지므로, Hibernate가 `mappedBy` 쪽 `@OneToOne`을 프록시로 지연 로딩하지 못해 주문을
  조회할 때마다 기록 조회 SELECT가 한 번 더 나간다(트레이드오프 11에서 감수하기로 한 비용). `Order.restore`는 CONFIRMED ⇔
  기록 존재 불변식을 검사해 어기면 `IllegalArgumentException`(한국어 메시지)을 던진다.

## 테스트 경량화 결정

24. **다른 층에서 이미 검증하는 중복만 지운다.** R02 필수 시나리오(동시성 6건, 롤백 검증)는 모두 유지한다.
25. **`@IntegrationTest`(MOCK)·`@E2ETest`(RANDOM_PORT) 공용 메타 어노테이션을 도입해 Spring 컨텍스트를 6개에서 2개로 줄인다.**
    둘 다 같은 spy 집합(OrderRepository, ProductJpaRepository, JdbcLikeCountAggregationDao)을 타입 수준 `@MockitoSpyBean`으로
    선언해 서로 다른 spy 조합이 만들던 컨텍스트 중복을 없앤다.
26. **무거운 테스트는 지우지 않고 태그로 나눈다.** `slow`(동시성·잠금 테스트)와 `example`(스캐폴딩 테스트) 태그를 붙이고,
    빠른 기본 실행 `test`는 두 태그를 제외, 새 `slowTest`는 두 태그만 실행, `check`는 둘 다 실행한다.
27. **Redis는 테스트 컨테이너만 제거한다.** commerce-api가 Redis를 쓰지 않는데도 테스트마다 컨테이너가 떴기 때문이며,
    운영 의존(Redis 헬스 체크 등)은 그대로 둔다.
28. **로그 설정과 `-Xshare:off`는 바꾸지 않는다.**
29. **DB 정리는 비어 있는 테이블을 TRUNCATE하지 않고, `@Transactional`로 롤백되는 클래스는 TRUNCATE 정리를 없앤다.**
    트랜잭션 밖에서 커밋하는 잠금 테스트만 정리를 유지한다.
30. **MySQL 테스트 컨테이너에 `withReuse(true)`를 추가한다.** 로컬의 `~/.testcontainers.properties`에
    `testcontainers.reuse.enable=true`가 있을 때만 동작하고 CI에는 영향이 없다.
31. **병렬로 실행한 Sonnet 에이전트들을 각각 git worktree로 분리했는데, git-ignored된
    `apps/commerce-api/src/test/resources/docker-java.properties`가 worktree에는 없어 Testcontainers가
    `BadRequestException (Status 400)`으로 Docker 연결에 실패했다.** 해당 파일을 각 worktree로 복사해 해결했다.
32. **사용량 한도로 중간에 멈춘 스트림 B는, 재개한 세션이 남은 변경을 검토하고 테스트까지 확인한 뒤 커밋했다.**
